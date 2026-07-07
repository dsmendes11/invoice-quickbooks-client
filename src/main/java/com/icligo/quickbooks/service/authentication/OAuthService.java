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
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
public class OAuthService {
    private static final String TOKEN_ENDPOINT = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer";
    private static final MediaType FORM_URLENCODED = MediaType.parse("application/x-www-form-urlencoded");
    
    private final QuickBooksConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Store current tokens in memory
    private volatile String currentAccessToken;
    private volatile String currentRefreshToken;
    private volatile long tokenExpiresAt;

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
}
