package com.ratelimiter.exception;

import com.ratelimiter.service.policy.PolicyValidator.ValidationResult;

public class PolicyValidationException extends RuntimeException {
    
    private final ValidationResult validationResult;
    
    public PolicyValidationException(String message) {
        super(message);
        this.validationResult = null;
    }
    
    public PolicyValidationException(ValidationResult validationResult) {
        super(validationResult.getErrorsAsString());
        this.validationResult = validationResult;
    }
    
    public PolicyValidationException(String message, ValidationResult validationResult) {
        super(message);
        this.validationResult = validationResult;
    }
    
    public ValidationResult getValidationResult() {
        return validationResult;
    }
    
    public boolean hasWarnings() {
        return validationResult != null && validationResult.hasWarnings();
    }
    
    public String getWarnings() {
        if (validationResult == null) {
            return null;
        }
        return validationResult.getWarningsAsString();
    }
}