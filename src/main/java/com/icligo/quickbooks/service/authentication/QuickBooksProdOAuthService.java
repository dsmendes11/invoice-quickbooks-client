package com.icligo.quickbooks.service.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;

/**
 * Keeps a valid production access token for read-only queries used by the sandbox-copy
 * feature. Intentionally has no authorization-code exchange, no disconnect, and no token
 * revocation — production credentials for this service are provisioned out-of-band
 * (via {@code quickbooks.prod.*} configuration), never through this app's own OAuth
 * "connect" flow, so there is no in-app path that could re-point this token at a
 * different (e.g. accidentally writable) company.
 */
@Slf4j
@Service
public class QuickBooksProdOAuthService {

    private static final String TOKEN_ENDPOINT = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer";
    private static final MediaType FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");

    private final QuickBooksConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile String currentAccessToken;
    private volatile String currentRefreshToken;
    private volatile long tokenExpiresAt;

    public QuickBooksProdOAuthService(@Qualifier("quickBooksProdConfig") QuickBooksConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder().build();
        this.objectMapper = new ObjectMapper();

        this.currentAccessToken = config.getAccessToken();
        this.currentRefreshToken = config.getRefreshToken();
        this.tokenExpiresAt = System.currentTimeMillis() + (3600 * 1000L);
    }

    public synchronized String getAccessToken() throws QuickBooksException {
        if (currentRefreshToken == null || currentRefreshToken.isEmpty()) {
            throw new QuickBooksException(
                    "No production refresh token configured. Set quickbooks.prod.* (or the matching env vars) before running a sandbox copy."
            );
        }
        if (isTokenExpiringSoon()) {
            refreshAccessToken();
        }
        return currentAccessToken;
    }

    private boolean isTokenExpiringSoon() {
        long fiveMinutesFromNow = System.currentTimeMillis() + (5 * 60 * 1000);
        return tokenExpiresAt < fiveMinutesFromNow;
    }

    private synchronized void refreshAccessToken() throws QuickBooksException {
        try {
            String credentials = config.getClientId() + ":" + config.getClientSecret();
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            String requestBody = "grant_type=refresh_token&refresh_token=" + currentRefreshToken;

            Request request = new Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .header("Authorization", basicAuth)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(requestBody, FORM_URLENCODED))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Production token refresh failed: {} - {}", response.code(), responseBody);
                    throw new QuickBooksException(
                            "Failed to refresh production token: " + response.code() + " - " + responseBody
                    );
                }

                JsonNode json = objectMapper.readTree(responseBody);

                this.currentAccessToken = json.get("access_token").asText();
                this.currentRefreshToken = json.has("refresh_token")
                        ? json.get("refresh_token").asText()
                        : currentRefreshToken;
                int expiresIn = json.get("expires_in").asInt();
                this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
            }
        } catch (IOException e) {
            log.error("Network error during production token refresh: {}", e.getMessage());
            throw new QuickBooksException("Network error during production token refresh", e);
        }
    }
}
