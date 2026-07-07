package com.icligo.quickbooks.config;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class AuthenticationTokenManager implements AuthenticationManager {

    String authHeaderValue;

    public AuthenticationTokenManager(String authHeaderValue) {
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String principal = (String) authentication.getPrincipal();

        if (!authHeaderValue.equals(principal)) {
            throw new BadCredentialsException("The API key was not found or not the expected value.");
        }
        authentication.setAuthenticated(true);
        return authentication;
    }
}
