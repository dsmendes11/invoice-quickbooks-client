package com.icligo.quickbooks.clients.quickbooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.icligo.quickbooks.service.authentication.OAuthService;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class QuickBooksClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final QuickBooksConfig config;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Autowired(required = false)
    private OAuthService oauthService;

    protected QuickBooksClient(QuickBooksConfig config) {
        this.config = config;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .addInterceptor(new AuthInterceptor())
                .addInterceptor(new LoggingInterceptor())
                .build();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void setOAuthService(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    protected ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    private String buildUrl(String endpoint) {
        String baseUrl = config.getBaseUrl();
        String realmId = config.getRealmId();

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        return baseUrl + "/v3/company/" + realmId + endpoint;
    }

    protected <T> T get(String endpoint, Class<T> responseType) throws QuickBooksException {
        String url = buildUrl(endpoint);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        return executeWithRetry(request, responseType);
    }

    protected <T> T post(String endpoint, Object body, Class<T> responseType) throws QuickBooksException {
        try {
            String url = buildUrl(endpoint);
            String jsonBody = objectMapper.writeValueAsString(body);

            RequestBody requestBody = RequestBody.create(jsonBody, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            return executeWithRetry(request, responseType);
        } catch (Exception e) {
            throw new QuickBooksException("Failed to serialize request body", e);
        }
    }

    protected <T> T put(String endpoint, Object body, Class<T> responseType) throws QuickBooksException {
        try {
            String url = buildUrl(endpoint);
            String jsonBody = objectMapper.writeValueAsString(body);

            RequestBody requestBody = RequestBody.create(jsonBody, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .put(requestBody)
                    .build();

            return executeWithRetry(request, responseType);
        } catch (Exception e) {
            throw new QuickBooksException("Failed to serialize request body", e);
        }
    }

    protected void delete(String endpoint) throws QuickBooksException {
        String url = buildUrl(endpoint);
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
        executeWithRetry(request, Void.class);
    }

    /**
     * Execute request with automatic retry on 401 (token expired).
     */
    private <T> T executeWithRetry(Request request, Class<T> responseType) throws QuickBooksException {
        try {
            return execute(request, responseType);
        } catch (QuickBooksException e) {
            // Check if it's an authentication error (401 or code 3200)
            if (isAuthenticationError(e)) {
                log.warn("Authentication failed (401), attempting token refresh...");

                // Try to refresh token
                if (oauthService != null && oauthService.hasRefreshToken()) {
                    try {
                        oauthService.forceRefresh();
                        log.info("Token refreshed, retrying request...");

                        // Retry the request with new token
                        return execute(request, responseType);

                    } catch (QuickBooksException refreshError) {
                        log.error("Failed to refresh token: {}", refreshError.getMessage());
                        throw new QuickBooksException(
                                "Authentication failed and token refresh failed. Please re-authenticate.",
                                refreshError
                        );
                    }
                } else {
                    throw new QuickBooksException(
                            "Authentication failed and no refresh token available. Please re-authenticate."
                    );
                }
            }

            // Not an auth error, rethrow
            throw e;
        }
    }

    private boolean isAuthenticationError(QuickBooksException e) {
        return e.getStatusCode() == 401 ||
                "3200".equals(e.getErrorCode()) ||
                (e.getMessage() != null && e.getMessage().contains("AuthenticationFailed"));
    }

    private <T> T execute(Request request, Class<T> responseType) throws QuickBooksException {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("QuickBooks API error: {} - {}", response.code(), responseBody);

                String errorCode = extractErrorCode(responseBody);
                String errorMessage = extractErrorMessage(responseBody);

                throw new QuickBooksException(response.code(),
                        errorCode,
                        errorMessage
                );
            }

            if (responseType == Void.class || responseType == String.class) {
                return responseType.cast(responseBody);
            }

            return objectMapper.readValue(responseBody, responseType);

        } catch (IOException e) {
            throw new QuickBooksException("Network error", e);
        }
    }

    private String extractErrorCode(String responseBody) {
        try {
            var tree = objectMapper.readTree(responseBody);
            if (tree.has("Fault")) {
                var error = tree.get("Fault").get("Error").get(0);
                return error.has("code") ? error.get("code").asText() : null;
            }
        } catch (Exception e) {
            log.debug("Could not extract error code", e);
        }
        return null;
    }

    private String extractErrorMessage(String responseBody) {
        try {
            var tree = objectMapper.readTree(responseBody);
            if (tree.has("Fault")) {
                var error = tree.get("Fault").get("Error").get(0);

                String message = error.has("Message") ? error.get("Message").asText() : "";
                String detail = error.has("Detail") ? error.get("Detail").asText() : "";

                if (!detail.isEmpty()) {
                    return message + ": " + detail;
                }
                return message;
            }
        } catch (Exception e) {
            log.debug("Could not extract error message", e);
        }
        return "Unknown error";
    }

    /**
     * Interceptor to add OAuth bearer token to requests.
     * Gets fresh token from OAuthService if available.
     */
    private class AuthInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            String token = config.getAccessToken();

            // Try to get fresh token from OAuthService if available
            if (oauthService != null) {
                try {
                    token = oauthService.getAccessToken();
                } catch (Exception e) {
                    log.warn("Failed to get fresh token from OAuthService: {}", e.getMessage());
                }
            }

            Request authorized = original.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .build();

            return chain.proceed(authorized);
        }
    }

    private static class LoggingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            log.debug("QB API: {} {}", request.method(), request.url());

            long start = System.currentTimeMillis();
            Response response = chain.proceed(request);

            log.debug("QB API: {} {} ({}ms)", response.code(), request.url(),
                    System.currentTimeMillis() - start);

            return response;
        }
    }
}