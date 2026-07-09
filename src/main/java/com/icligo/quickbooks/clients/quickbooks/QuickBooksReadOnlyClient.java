package com.icligo.quickbooks.clients.quickbooks;

import com.icligo.quickbooks.service.authentication.QuickBooksProdOAuthService;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Bound to the production QuickBooks connection. This class implements exactly one
 * operation — an SoQL {@link #query(String)} over HTTP GET — and nothing else. There is
 * no post/put/delete/batch method anywhere in this class, by design: it is the boundary
 * that guarantees the sandbox-copy feature cannot write to production, even by mistake.
 * Do not add mutating methods here; production writes are out of scope for this client.
 */
@Slf4j
@Component
public class QuickBooksReadOnlyClient {

    private final OkHttpClient httpClient;
    private final QuickBooksConfig config;
    private final QuickBooksProdOAuthService oAuthService;

    public QuickBooksReadOnlyClient(@Qualifier("quickBooksProdConfig") QuickBooksConfig config,
                                     QuickBooksProdOAuthService oAuthService) {
        this.config = config;
        this.oAuthService = oAuthService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .addInterceptor(new AuthInterceptor())
                .build();
    }

    public String getRealmId() {
        return config.getRealmId();
    }

    public String getBaseUrl() {
        return config.getBaseUrl();
    }

    public String query(String soqlQuery) throws QuickBooksException {
        String baseUrl = config.getBaseUrl().endsWith("/")
                ? config.getBaseUrl().substring(0, config.getBaseUrl().length() - 1)
                : config.getBaseUrl();
        String url = baseUrl + "/v3/company/" + config.getRealmId() + "/query?query="
                + java.net.URLEncoder.encode(soqlQuery, StandardCharsets.UTF_8);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("QuickBooks production query failed: {} - {}", response.code(), responseBody);
                throw new QuickBooksException(response.code(), null, responseBody);
            }

            return responseBody;
        } catch (IOException e) {
            throw new QuickBooksException("Network error querying production QuickBooks", e);
        }
    }

    private class AuthInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request original = chain.request();

            String token;
            try {
                token = oAuthService.getAccessToken();
            } catch (QuickBooksException e) {
                throw new IOException("Failed to obtain production access token", e);
            }

            Request authorized = original.newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .build();

            return chain.proceed(authorized);
        }
    }
}
