package com.icligo.quickbooks.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field validation for {@link com.icligo.quickbooks.model.QuickBooksDocument}:
 * enforces the fields required by each {@code type} (serviceId/productId/refundId) and the
 * "at least one customer identifier" rule that {@code CustomerService} otherwise only
 * discovers deep inside the Temporal workflow.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QuickBooksDocumentValidator.class)
public @interface ValidQuickBooksDocument {
    String message() default "Invalid QuickBooks document";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
