package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.model.SandboxCopyResult;
import com.icligo.quickbooks.service.SandboxCopyService;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service endpoint (protected by the standard API bearer token, see
 * SecurityConfig) that copies entities from the production QuickBooks company into the
 * sandbox company — the equivalent of running {@code qbo-sandbox <entity>} as a single call.
 */
@Slf4j
@RestController
@RequestMapping("${api.base-path}/sandbox-copy")
@RequiredArgsConstructor
public class SandboxCopyController {

    private final SandboxCopyService sandboxCopyService;

    @PostMapping("/{entity}")
    public ResponseEntity<SandboxCopyResult> copy(@PathVariable String entity,
                                                    @RequestParam(defaultValue = "1000") int maxResults,
                                                    @RequestParam(defaultValue = "30") int batchSize) throws QuickBooksException {
        log.info("POST /sandbox-copy/{} – maxResults={}, batchSize={}", entity, maxResults, batchSize);
        return ResponseEntity.ok(sandboxCopyService.copyToSandbox(entity, maxResults, batchSize));
    }
}
