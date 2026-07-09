package com.icligo.quickbooks.config;

import com.icligo.quickbooks.util.QuickBooksConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production QuickBooks connection, used exclusively as a read-only source for the
 * sandbox-copy feature. Deliberately kept separate from {@link ApplicationConfig}'s
 * (sandbox/write) bean, with its own credentials, so no production write path can ever
 * be reached through this configuration. Credentials must be supplied via environment
 * variables — unlike the sandbox config, no defaults are hardcoded here.
 */
@Configuration
public class ProdApplicationConfig {

    @Value("${quickbooks.prod.base-url:https://quickbooks.api.intuit.com}")
    private String baseUrl;

    @Value("${quickbooks.prod.realm-id:}")
    private String realmId;

    @Value("${quickbooks.prod.client-id:}")
    private String clientId;

    @Value("${quickbooks.prod.client-secret:}")
    private String clientSecret;

    @Value("${quickbooks.prod.access-token:}")
    private String accessToken;

    @Value("${quickbooks.prod.refresh-token:}")
    private String refreshToken;

    @Value("${quickbooks.api.connect-timeout:30000}")
    private int connectTimeout;

    @Value("${quickbooks.api.read-timeout:30000}")
    private int readTimeout;

    @Bean(name = "quickBooksProdConfig")
    public QuickBooksConfig quickBooksProdConfig() {
        return QuickBooksConfig.builder()
                .baseUrl(baseUrl)
                .realmId(realmId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }
}
