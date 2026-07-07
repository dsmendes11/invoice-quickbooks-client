package com.icligo.quickbooks.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${api.base-path}")
    String basePath;
    @Value("${spring.security.cors.allow-credentials}")
    Boolean allowCredentials;
    @Value("${spring.security.cors.allowed-origins}")
    List<String> allowOrigins;
    @Value("${spring.security.cors.allowed-methods}")
    List<String> allowMethods;
    @Value("${spring.security.cors.allowed-headers}")
    List<String> allowHeaders;

    @Value("${spring.application.tokenName}")
    private String authHeaderName;

    @Value("${spring.application.tokenValue}")
    private String authHeaderValue;


    @Bean
    AuthenticationManagerResolver<HttpServletRequest> tokenAuthenticationManagerResolver() {
        AuthenticationTokenManager authenticationTokenManager = new AuthenticationTokenManager(authHeaderValue);
        return (request) -> authenticationTokenManager;
    }

    @Bean
    MvcRequestMatcher.Builder mvc(HandlerMappingIntrospector introspector) {
        return new MvcRequestMatcher.Builder(introspector);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManagerResolver<HttpServletRequest> tokenAuthenticationManagerResolver,
                                                   MvcRequestMatcher.Builder mvc) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable).cors(corsSpec -> {
            CorsConfiguration corsConfig = new CorsConfiguration();
            corsConfig.setAllowCredentials(allowCredentials);
            corsConfig.setAllowedOrigins(allowOrigins);
            allowMethods.forEach(corsConfig::addAllowedMethod);
            allowHeaders.forEach(corsConfig::addAllowedHeader);
            UrlBasedCorsConfigurationSource source =
                    new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", corsConfig);
            corsSpec.configurationSource(source);
        });
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(mvc.pattern(basePath + "/**")).authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(tokenAuthenticationManagerResolver)
                        .bearerTokenResolver((BearerTokenResolver) this::tokenExtractor));
        return http.build();
    }

    public String tokenExtractor(HttpServletRequest request) {
        return request.getHeader(authHeaderName);
    }
}