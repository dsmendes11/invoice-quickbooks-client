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
    private String accessToken;
    private String refreshToken;
    private String redirectUri;

    @Builder.Default
    private int connectTimeout = 30000;

    @Builder.Default
    private int readTimeout = 30000;

    @Builder.Default
    private int writeTimeout = 30000;
}
