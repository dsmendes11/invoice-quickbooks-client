package com.icligo.quickbooks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field validation for {@link com.icligo.quickbooks.model.QuickBooksDocument}: enforces
 * {@code type} (against {@link com.icligo.quickbooks.enums.SalesDocumentTypes} — the abbreviated
 * codes INV/SRT/RRT, not the old long-form names) and {@code items} (against QuickBooks' own
 * Items — see {@code QuickBooksDocumentValidator.validItems}). {@code type=RRT} (RefundReceipt)
 * is rejected outright here: refunds are allocation-driven via {@code POST /refunds} (see
 * {@link com.icligo.quickbooks.service.RefundReceiptAllocationService}), not caller-specified
 * through this generic document-creation path. {@code serviceId}/{@code productId} are
 * unconditionally required via plain {@code @NotBlank} on the fields themselves (every type
 * needs both, for cross-type context — though only {@code productId} feeds into the internal
 * controlKey), as is {@code clientInvoiceInfo} (name/address/country) via {@code @NotBlank} on
 * {@link com.icligo.quickbooks.model.document.ClientInvoiceInfo}, cascaded by {@code @Valid}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QuickBooksDocumentValidator.class)
public @interface ValidQuickBooksDocument {
    String message() default "Invalid QuickBooks document";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
