package com.icligo.quickbooks.util;

import lombok.Getter;

@Getter
public class QuickBooksException extends Exception {
    
    private final int statusCode;
    private final String errorCode;
    private final String errorMessage;
    
    public QuickBooksException(String message) {
        super(message);
        this.statusCode = 0;
        this.errorCode = null;
        this.errorMessage = message;
    }
    
    public QuickBooksException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorCode = null;
        this.errorMessage = message;
    }
    
    public QuickBooksException(int statusCode, String errorCode, String errorMessage) {
        super(String.format("QuickBooks API Error [%d]: %s - %s", statusCode, errorCode, errorMessage));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
