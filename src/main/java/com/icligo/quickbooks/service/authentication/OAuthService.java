package com.icligo.quickbooks.service.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icligo.quickbooks.model.StoredTokens;
import com.icligo.quickbooks.repository.StoredTokensRepository;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class OAuthService {
    private static final String TOKEN_ENDPOINT = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer";
    private static final String AUTHORIZATION_ENDPOINT = "https://appcenter.intuit.com/connect/oauth2";
    private static final String REVOKE_ENDPOINT = "https://developer.api.intuit.com/v2/oauth2/tokens/revoke";
    private static final String SCOPE = "com.intuit.quickbooks.accounting";
    private static final MediaType FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final QuickBooksConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Store current tokens in memory
    private volatile String currentAccessToken;
    private volatile String currentRefreshToken;
    private volatile long tokenExpiresAt;

    // CSRF state for the in-flight authorization request (single-tenant app, one connection at a time)
    private volatile String pendingState;

    private final StoredTokensRepository storedTokensRepository;

    public OAuthService(QuickBooksConfig config, StoredTokensRepository storedTokensRepository) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder().build();
        this.objectMapper = new ObjectMapper();
        
        // Initialize with config tokens
        this.currentAccessToken = config.getAccessToken();
        this.currentRefreshToken = config.getRefreshToken();
        this.tokenExpiresAt = System.currentTimeMillis() + (3600 * 1000);

        this.storedTokensRepository = storedTokensRepository;
    }

    public synchronized String getAccessToken() throws QuickBooksException {
        if (isTokenExpiringSoon()) {
            refreshAccessToken();
        }
        
        return currentAccessToken;
    }

    private boolean isTokenExpiringSoon() {
        long fiveMinutesFromNow = System.currentTimeMillis() + (5 * 60 * 1000);
        return tokenExpiresAt < fiveMinutesFromNow;
    }

    public synchronized void refreshAccessToken() throws QuickBooksException {
        if (currentRefreshToken == null || currentRefreshToken.isEmpty()) {
            throw new QuickBooksException("No refresh token available. Please re-authenticate.");
        }
        
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
                    log.error("Token refresh failed: {} - {}", response.code(), responseBody);
                    throw new QuickBooksException(
                            "Failed to refresh token: " + response.code() + " - " + responseBody
                    );
                }

                JsonNode json = objectMapper.readTree(responseBody);
                
                String newAccessToken = json.get("access_token").asText();
                String newRefreshToken = json.has("refresh_token") 
                        ? json.get("refresh_token").asText() 
                        : currentRefreshToken;
                int expiresIn = json.get("expires_in").asInt();
                long refreshTokenExpiresIn = json.get("x_refresh_token_expires_in").asLong();

                this.currentAccessToken = newAccessToken;
                this.currentRefreshToken = newRefreshToken;
                this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000);

                config.setAccessToken(newAccessToken);
                config.setRefreshToken(newRefreshToken);

                List<StoredTokens> tokens = storedTokensRepository.findAll();
                StoredTokens token;
                if(tokens.isEmpty()) {
                    token = new StoredTokens();
                }else{
                    token = tokens.getFirst();
                }
                token.setAccessToken(newAccessToken);
                token.setRefreshToken(newRefreshToken);
                token.setExpiresIn(expiresIn);
                token.setRefreshTokenExpiresIn(refreshTokenExpiresIn);
                storedTokensRepository.save(token);
            }
        } catch (IOException e) {
            log.error("Network error during token refresh: {}", e.getMessage());
            throw new QuickBooksException("Network error during token refresh", e);
        } catch (Exception e) {
            log.error("Unexpected error during token refresh: {}", e.getMessage());
            throw new QuickBooksException("Failed to refresh access token", e);
        }
    }

    public void forceRefresh() throws QuickBooksException {
        refreshAccessToken();
    }

    public boolean hasRefreshToken() {
        return currentRefreshToken != null && !currentRefreshToken.isEmpty();
    }

    public long getSecondsUntilExpiration() {
        return (tokenExpiresAt - System.currentTimeMillis()) / 1000;
    }

    public boolean isConnected() {
        return hasRefreshToken();
    }

    public synchronized String buildAuthorizationUrl() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        pendingState = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        return AUTHORIZATION_ENDPOINT
                + "?client_id=" + encode(config.getClientId())
                + "&response_type=code"
                + "&scope=" + encode(SCOPE)
                + "&redirect_uri=" + encode(config.getRedirectUri())
                + "&state=" + encode(pendingState);
    }

    public synchronized void exchangeAuthorizationCode(String code, String state, String realmId) throws QuickBooksException {
        if (pendingState == null || !pendingState.equals(state)) {
            throw new QuickBooksException("Invalid or expired OAuth state parameter.");
        }
        pendingState = null;

        try {
            String credentials = config.getClientId() + ":" + config.getClientSecret();
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

            String requestBody = "grant_type=authorization_code"
                    + "&code=" + encode(code)
                    + "&redirect_uri=" + encode(config.getRedirectUri());

            Request request = new Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .header("Authorization", basicAuth)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(requestBody, FORM_URLENCODED))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.error("Authorization code exchange failed: {} - {}", response.code(), responseBody);
                    throw new QuickBooksException(
                            "Failed to exchange authorization code: " + response.code() + " - " + responseBody
                    );
                }

                JsonNode json = objectMapper.readTree(responseBody);

                String newAccessToken = json.get("access_token").asText();
                String newRefreshToken = json.get("refresh_token").asText();
                int expiresIn = json.get("expires_in").asInt();
                long refreshTokenExpiresIn = json.get("x_refresh_token_expires_in").asLong();

                this.currentAccessToken = newAccessToken;
                this.currentRefreshToken = newRefreshToken;
                this.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L);

                config.setAccessToken(newAccessToken);
                config.setRefreshToken(newRefreshToken);
                config.setRealmId(realmId);

                List<StoredTokens> tokens = storedTokensRepository.findAll();
                StoredTokens token = tokens.isEmpty() ? new StoredTokens() : tokens.getFirst();
                token.setAccessToken(newAccessToken);
                token.setRefreshToken(newRefreshToken);
                token.setExpiresIn(expiresIn);
                token.setRefreshTokenExpiresIn(refreshTokenExpiresIn);
                token.setRealmId(realmId);
                storedTokensRepository.save(token);
            }
        } catch (IOException e) {
            log.error("Network error during authorization code exchange: {}", e.getMessage());
            throw new QuickBooksException("Network error during authorization code exchange", e);
        }
    }

    public synchronized void disconnect() throws QuickBooksException {
        String tokenToRevoke = currentRefreshToken != null ? currentRefreshToken : currentAccessToken;

        if (tokenToRevoke != null && !tokenToRevoke.isEmpty()) {
            try {
                String credentials = config.getClientId() + ":" + config.getClientSecret();
                String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

                String requestBody = objectMapper.writeValueAsString(new RevokeRequest(tokenToRevoke));

                Request request = new Request.Builder()
                        .url(REVOKE_ENDPOINT)
                        .header("Authorization", basicAuth)
                        .header("Accept", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String responseBody = response.body() != null ? response.body().string() : "";
                        log.warn("Token revocation returned non-success status: {} - {}", response.code(), responseBody);
                    }
                }
            } catch (IOException e) {
                log.warn("Network error while revoking QuickBooks token, clearing local state anyway: {}", e.getMessage());
            }
        }

        currentAccessToken = null;
        currentRefreshToken = null;
        tokenExpiresAt = 0;
        config.setAccessToken(null);
        config.setRefreshToken(null);

        storedTokensRepository.deleteAll();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record RevokeRequest(String token) {
    }
}
