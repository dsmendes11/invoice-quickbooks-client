package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.service.authentication.OAuthService;
import com.icligo.quickbooks.util.QuickBooksException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Endpoints registered with Intuit as the app's Launch URL, Disconnect URL and
 * Connect/Reconnect URL, and hit directly by a browser during the "Connect to QuickBooks"
 * flow. Not part of the API other services integrate against — hidden from the public
 * Swagger UI (see {@link com.icligo.quickbooks.controller.DocumentController} for that API).
 * {@code connect}/{@code callback} are public per SecurityConfig; {@code disconnect}/{@code launch}
 * require the same shared-secret header as the rest of the API.
 */
@Hidden
@Slf4j
@RestController
@RequestMapping("/quickbooks")
@RequiredArgsConstructor
public class QuickBooksOAuthController {

    private final OAuthService oAuthService;

    @GetMapping("/connect")
    public void connect(HttpServletResponse response) throws IOException {
        String authorizationUrl = oAuthService.buildAuthorizationUrl();
        response.sendRedirect(authorizationUrl);
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false) String realmId,
                                            @RequestParam(required = false) String error) {
        if (error != null) {
            log.warn("QuickBooks authorization was not granted: {}", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("QuickBooks authorization failed: " + error);
        }

        if (code == null || state == null || realmId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Missing required parameters from QuickBooks callback.");
        }

        try {
            oAuthService.exchangeAuthorizationCode(code, state, realmId);
        } catch (QuickBooksException e) {
            log.error("Failed to complete QuickBooks authorization: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to complete QuickBooks authorization: " + e.getMessage());
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("QuickBooks account connected successfully. You can close this window.");
    }

    @GetMapping("/disconnect")
    public ResponseEntity<String> disconnect() {
        try {
            oAuthService.disconnect();
        } catch (QuickBooksException e) {
            log.error("Failed to disconnect QuickBooks: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to disconnect QuickBooks: " + e.getMessage());
        }

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("QuickBooks account disconnected.");
    }

    @GetMapping("/launch")
    public ResponseEntity<String> launch() {
        String status = oAuthService.isConnected() ? "connected" : "not connected";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("Invoice QuickBooks Service is running (" + status + ").");
    }
}
