package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("${api.base-path}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final TemporalDocumentService temporalDocumentService;

    /**
     * Create a QuickBooks document via Temporal workflow.
     *
     * <p>Supported types (field {@code type}):
     * <ul>
     *   <li>{@code INVOICE}</li>
     *   <li>{@code SALES_RECEIPT}</li>
     *   <li>{@code REFUND_RECEIPT}</li>
     * </ul>
     *
     * <p>The customer is automatically created in QuickBooks if it doesn't exist.
     * The workflow is retried on transient failures.
     */
    @PostMapping
    public ResponseEntity<QuickBooksDocument> create(@Valid @RequestBody QuickBooksDocument document) {
        log.info("POST /documents – type={}", document.getType());
        QuickBooksDocument created = temporalDocumentService.create(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
