package com.icligo.quickbooks.config;
import com.icligo.quickbooks.model.StoredTokens;
import com.icligo.quickbooks.repository.StoredTokensRepository;
import com.icligo.quickbooks.util.QuickBooksConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class ApplicationConfig {

    @Value("${quickbooks.api.base-url}")
    private String baseUrl;

    @Value("${quickbooks.api.realm-id}")
    private String realmId;

    @Value("${quickbooks.api.client-id}")
    private String clientId;

    @Value("${quickbooks.api.client-secret}")
    private String clientSecret;

    @Value("${quickbooks.api.access-token}")
    private String accessToken;

    @Value("${quickbooks.api.refresh-token}")
    private String refreshToken;

    @Value("${quickbooks.oauth.redirect-uri}")
    private String redirectUri;

    @Value("${quickbooks.api.connect-timeout:30000}")
    private int connectTimeout;

    @Value("${quickbooks.api.read-timeout:30000}")
    private int readTimeout;

    @Value("${quickbooks.api.write-timeout:30000}")
    private int writeTimeout;

    /**
     * The sandbox/write-target connection. Marked {@code @Primary} so existing unqualified
     * {@code QuickBooksConfig} injections keep resolving to this bean once a second
     * (production, read-only) bean is registered by {@link QuickBooksProdConfig}.
     */
    @Bean
    @Primary
    public QuickBooksConfig quickBooksConfig(StoredTokensRepository storedTokensRepository) {
        List<StoredTokens> tokens = storedTokensRepository.findAll();
        if (tokens.isEmpty()) {
            return QuickBooksConfig.builder()
                    .baseUrl(baseUrl)
                    .realmId(realmId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .redirectUri(redirectUri)
                    .connectTimeout(connectTimeout)
                    .readTimeout(readTimeout)
                    .writeTimeout(writeTimeout)
                    .build();
        }else{
            StoredTokens stored = tokens.getFirst();
            return QuickBooksConfig.builder()
                    .baseUrl(baseUrl)
                    .realmId(stored.getRealmId() != null ? stored.getRealmId() : realmId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .accessToken(stored.getAccessToken())
                    .refreshToken(stored.getRefreshToken())
                    .redirectUri(redirectUri)
                    .connectTimeout(connectTimeout)
                    .readTimeout(readTimeout)
                    .writeTimeout(writeTimeout)
                    .build();
        }

    }
}
