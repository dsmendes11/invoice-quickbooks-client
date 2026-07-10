package com.icligo.quickbooks.validation;

import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.service.ItemService;
import com.icligo.quickbooks.util.QuickBooksException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;
import java.util.List;

/**
 * Autowired by Spring Boot's {@code SpringConstraintValidatorFactory} (enabled automatically
 * with {@code spring-boot-starter-validation} on the classpath), so {@link ItemService} is
 * injected like any other Spring bean despite this class not being a {@code @Component} itself.
 */
public class QuickBooksDocumentValidator implements ConstraintValidator<ValidQuickBooksDocument, QuickBooksDocument> {

    private final ItemService itemService;

    public QuickBooksDocumentValidator(ItemService itemService) {
        this.itemService = itemService;
    }

    @Override
    public boolean isValid(QuickBooksDocument document, ConstraintValidatorContext context) {
        if (document == null || isBlank(document.getType())) {
            return true; // @NotBlank on `type` reports this
        }

        String type = document.getType().toUpperCase();
        context.disableDefaultConstraintViolation();

        if (type.equals(SalesDocumentTypes.REFUND_RECEIPT.getValue())) {
            violation(context, "type",
                    "RefundReceipt creation is not accepted here — refunds are allocated, not caller-specified; use POST /refunds instead");
            return false;
        }

        if (type.equals(SalesDocumentTypes.CREDIT_MEMO.getValue())) {
            violation(context, "type",
                    "CreditMemo creation is not accepted here — CreditMemos are created internally when a booking Invoice cancels prior Sales Receipts");
            return false;
        }

        boolean valid = true;

        if (checkEnumTypes(document)) {
            if (!SalesDocumentTypes.exists(type)) {
                violation(context, "type", "must be one of INV, SRT, RRT, CDM");
                return false;
            }
            violation(context, "items",
                    "must be present and reference existing QuickBooks Items, with non-negative values and a non-zero total");
            valid = false;
        }

        return valid;
    }

    /**
     * Mirrors the invoice-management-system's {@code CheckEnumTypes} — {@code true} means
     * something is invalid (matching the reference's inverted naming, adjusted here to Java's
     * lowerCamelCase convention), checking {@code type} against {@link SalesDocumentTypes} and
     * delegating item validation to {@link #validItems}.
     */
    private boolean checkEnumTypes(QuickBooksDocument document) {
        return (document.getType() != null && !SalesDocumentTypes.exists(document.getType()))
                || (document.getItems() == null || !validItems(document.getItems()));
    }

    /**
     * Mirrors the invoice-management-system's {@code validItems}, adapted to validate against
     * QuickBooks' own Items (via {@link ItemService}) instead of Primavera's local item/tax map:
     * every item must reference an existing QuickBooks Item by name, have a non-negative value,
     * and the line total must be non-zero.
     */
    private boolean validItems(List<ItemDto> items) {
        BigDecimal totalValue = BigDecimal.ZERO;
        for (ItemDto item : items) {
            if (item.getValue() == null || isBlank(item.getItem())) {
                return false;
            }
            totalValue = totalValue.add(item.getValue());
            if (item.getValue().doubleValue() < 0) {
                return false;
            }
            try {
                if (itemService.findByName(item.getItem()) == null) {
                    return false;
                }
            } catch (QuickBooksException e) {
                return false;
            }
        }
        return totalValue.compareTo(BigDecimal.ZERO) != 0;
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
