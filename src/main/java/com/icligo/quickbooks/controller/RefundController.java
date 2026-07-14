package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.model.CreateRefundRequestDto;
import com.icligo.quickbooks.service.RefundReceiptAllocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The only supported way to create a RefundReceipt (see {@code QuickBooksDocumentValidator}, which
 * rejects {@code type=RRT} on {@code POST /documents}) — refunds are allocation-driven, not
 * caller-specified, see {@link RefundReceiptAllocationService}.
 */
@Slf4j
@RestController
@RequestMapping("${api.base-path}/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundReceiptAllocationService refundReceiptAllocationService;

    @PostMapping
    public ResponseEntity<List<Object>> create(@Valid @RequestBody CreateRefundRequestDto request) {
        log.info("POST /refunds – serviceId={}, refundId={}, value={}",
                request.getServiceId(), request.getRefundId(), request.getValue());
        return ResponseEntity.ok(refundReceiptAllocationService.createAllocatedRefundReceipts(request));
    }
}
