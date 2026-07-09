package com.icligo.quickbooks.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxCopyResult {
    private String entity;
    private String prodRealmId;
    private String sandboxRealmId;
    private int fetchedFromProd;
    private int submitted;
    private int succeeded;
    private int failed;
    private List<String> failureMessages;
}
