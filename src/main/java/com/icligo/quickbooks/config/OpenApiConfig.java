package com.icligo.quickbooks.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every request under {@code api.base-path} is protected by a single static shared-secret
 * header (see {@link SecurityConfig}/{@link AuthenticationTokenManager}), not OAuth2/JWT.
 * This registers that header as the OpenAPI security scheme so "Authorize" in Swagger UI
 * attaches it to every "Try it out" call.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "apiToken";

    @Value("${spring.application.tokenName}")
    private String tokenHeaderName;

    @Bean
    public OpenAPI invoiceQuickBooksOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Invoice QuickBooks Service")
                        .description("""
                                Internal service that creates QuickBooks invoices, sales receipts \
                                and refund receipts on behalf of other icligo services, via Temporal \
                                workflows. Authentication is a single static shared-secret header — \
                                see the security scheme below.""")
                        .version("v1")
                        .contact(new Contact().name("icligo engineering")))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(tokenHeaderName)
                                .description("Shared secret configured via `spring.application.tokenValue`. "
                                        + "Sent as-is, not as a Bearer token.")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
