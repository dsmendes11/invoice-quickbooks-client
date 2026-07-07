package com.icligo.quickbooks.validation;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

public class QuickBooksDocumentValidator implements ConstraintValidator<ValidQuickBooksDocument, QuickBooksDocument> {

    private static final Set<String> VALID_TYPES = Set.of("INVOICE", "SALES_RECEIPT", "REFUND_RECEIPT");

    @Override
    public boolean isValid(QuickBooksDocument document, ConstraintValidatorContext context) {
        if (document == null || isBlank(document.getType())) {
            return true; // @NotBlank on `type` reports this
        }

        String type = document.getType().toUpperCase();
        context.disableDefaultConstraintViolation();
        boolean valid = true;

        if (!VALID_TYPES.contains(type)) {
            violation(context, "type", "must be one of INVOICE, SALES_RECEIPT, REFUND_RECEIPT");
            return false;
        }

        if (type.equals("INVOICE") && isBlank(document.getServiceId())) {
            violation(context, "serviceId", "is required when type is INVOICE");
            valid = false;
        }
        if ((type.equals("SALES_RECEIPT") || type.equals("REFUND_RECEIPT")) && isBlank(document.getProductId())) {
            violation(context, "productId", "is required when type is " + type);
            valid = false;
        }
        if (type.equals("REFUND_RECEIPT") && isBlank(document.getRefundId())) {
            violation(context, "refundId", "is required when type is REFUND_RECEIPT");
            valid = false;
        }

        ClientInvoiceInfo info = document.getClientInvoiceInfo();
        if (info != null && isBlank(info.getName()) && isBlank(info.getEmail())
                && isBlank(info.getAddress()) && isBlank(info.getCountry())) {
            violation(context, "clientInvoiceInfo",
                    "must have at least one of: name, email, address, country");
            valid = false;
        }

        return valid;
    }

    private void violation(ConstraintValidatorContext context, String property, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(property)
                .addConstraintViolation();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
