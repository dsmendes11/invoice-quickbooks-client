package com.icligo.quickbooks.util;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuickBooksConfig {

    private String baseUrl;
    private String realmId;
    private String clientId;
    private String clientSecret;

    // Mutated by OAuthService from synchronized methods, read unsynchronized by
    // QuickBooksClient.AuthInterceptor from OkHttp dispatcher threads — volatile for
    // cross-thread visibility (OAuthService's own synchronization doesn't cover this reader).
    private volatile String accessToken;
    private volatile String refreshToken;

    private String redirectUri;

    @Builder.Default
    private int connectTimeout = 30000;

    @Builder.Default
    private int readTimeout = 30000;

    @Builder.Default
    private int writeTimeout = 30000;
}
