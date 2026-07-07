package com.icligo.quickbooks.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties("spring.security")
public class ApplicationSecurityConfig {

    public enum SecurityType {authenticated, permitAll, hasAnyAuthority, denyAll}

    SecurityType defaultType;
    List<PathSecurity> paths;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathSecurity {
        String path;
        SecurityType type;
        String[] authorities;
        HttpMethod method;
    }

}

