package com.icligo.quickbooks.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a line item on a PrimaveraDocument invoice.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ItemDto {
    String item;
    String description;
    String tax;
    BigDecimal value;
    BigDecimal discount;
    String locator;
    LocalDateTime itemDate;
}
