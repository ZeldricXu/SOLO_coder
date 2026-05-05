package com.ratelimiter.service.policy;

import com.ratelimiter.model.RateLimitPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class PolicyValidator {
    
    private static final int MIN_THRESHOLD = 1;
    private static final int MAX_THRESHOLD = 1000000;
    private static final int MIN_WINDOW_SIZE = 1;
    private static final int MAX_WINDOW_SIZE = 86400;
    private static final int MIN_BURST_SIZE = 0;
    private static final int MAX_BURST_SIZE = 100000;
    private static final int MIN_RESPONSE_CODE = 200;
    private static final int MAX_RESPONSE_CODE = 599;
    
    private static final List<String> VALID_ALGORITHMS = Arrays.asList(
            "fixed_window",
            "sliding_window", 
            "token_bucket",
            "FIXED_WINDOW",
            "SLIDING_WINDOW",
            "TOKEN_BUCKET"
    );
    
    private static final List<String> VALID_TARGET_TYPES = Arrays.asList(
            "api_path",
            "client_id",
            "user_id",
            "ip_address",
            "default"
    );
    
    private static final List<String> VALID_ACTIONS = Arrays.asList(
            "reject",
            "delay",
            "degrade"
    );
    
    public ValidationResult validate(RateLimitPolicy policy) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (policy == null) {
            return ValidationResult.fail("Policy cannot be null");
        }
        
        validatePolicyId(policy.getPolicyId(), errors, warnings);
        validateTargetType(policy.getTargetType(), errors);
        validateTargetValue(policy.getTargetValue(), errors);
        validateAlgorithm(policy.getAlgorithm(), errors);
        validateThreshold(policy.getThreshold(), errors);
        validateWindowSize(policy.getWindowSize(), errors);
        validateBurstSize(policy.getBurstSize(), errors, warnings);
        validateActionOnLimit(policy.getActionOnLimit(), errors, warnings);
        validateResponseCode(policy.getResponseCode(), errors, warnings);
        validateResponseMessage(policy.getResponseMessage(), warnings);
        
        if (!errors.isEmpty()) {
            return ValidationResult.fail(errors);
        }
        
        return ValidationResult.success(warnings);
    }
    
    private void validatePolicyId(String policyId, List<String> errors, List<String> warnings) {
        if (policyId == null || policyId.trim().isEmpty()) {
            warnings.add("Policy ID is not provided, will be auto-generated");
        } else if (policyId.length() > 255) {
            errors.add("Policy ID is too long (max 255 characters)");
        } else if (!policyId.matches("^[a-zA-Z0-9_\\-]+$")) {
            warnings.add("Policy ID contains special characters, recommended to use only alphanumeric, underscore, or hyphen");
        }
    }
    
    private void validateTargetType(String targetType, List<String> errors) {
        if (targetType == null || targetType.trim().isEmpty()) {
            errors.add("Target type is required");
            return;
        }
        
        if (!VALID_TARGET_TYPES.contains(targetType.toLowerCase())) {
            errors.add("Invalid target type: " + targetType + ". Valid types: " + VALID_TARGET_TYPES);
        }
    }
    
    private void validateTargetValue(String targetValue, List<String> errors) {
        if (targetValue == null || targetValue.trim().isEmpty()) {
            errors.add("Target value is required");
            return;
        }
        
        if (targetValue.length() > 500) {
            errors.add("Target value is too long (max 500 characters)");
        }
    }
    
    private void validateAlgorithm(String algorithm, List<String> errors) {
        if (algorithm == null || algorithm.trim().isEmpty()) {
            errors.add("Algorithm is required");
            return;
        }
        
        if (!VALID_ALGORITHMS.contains(algorithm)) {
            errors.add("Invalid algorithm: " + algorithm + ". Valid algorithms: " + 
                    Arrays.asList("fixed_window", "sliding_window", "token_bucket"));
        }
    }
    
    private void validateThreshold(int threshold, List<String> errors) {
        if (threshold < MIN_THRESHOLD) {
            errors.add("Threshold must be at least " + MIN_THRESHOLD);
        }
        if (threshold > MAX_THRESHOLD) {
            errors.add("Threshold must be at most " + MAX_THRESHOLD);
        }
    }
    
    private void validateWindowSize(int windowSize, List<String> errors) {
        if (windowSize < MIN_WINDOW_SIZE) {
            errors.add("Window size must be at least " + MIN_WINDOW_SIZE + " second(s)");
        }
        if (windowSize > MAX_WINDOW_SIZE) {
            errors.add("Window size must be at most " + MAX_WINDOW_SIZE + " seconds (24 hours)");
        }
    }
    
    private void validateBurstSize(int burstSize, List<String> errors, List<String> warnings) {
        if (burstSize < MIN_BURST_SIZE) {
            errors.add("Burst size must be at least " + MIN_BURST_SIZE);
        }
        if (burstSize > MAX_BURST_SIZE) {
            errors.add("Burst size must be at most " + MAX_BURST_SIZE);
        }
        if (burstSize == 0) {
            warnings.add("Burst size is 0, no burst capacity allowed");
        }
    }
    
    private void validateActionOnLimit(String actionOnLimit, List<String> errors, List<String> warnings) {
        if (actionOnLimit == null || actionOnLimit.trim().isEmpty()) {
            warnings.add("Action on limit is not provided, defaulting to 'reject'");
            return;
        }
        
        if (!VALID_ACTIONS.contains(actionOnLimit.toLowerCase())) {
            errors.add("Invalid action on limit: " + actionOnLimit + ". Valid actions: " + VALID_ACTIONS);
        }
    }
    
    private void validateResponseCode(int responseCode, List<String> errors, List<String> warnings) {
        if (responseCode == 0) {
            warnings.add("Response code is not provided, defaulting to 429");
            return;
        }
        
        if (responseCode < MIN_RESPONSE_CODE || responseCode > MAX_RESPONSE_CODE) {
            errors.add("Response code must be between " + MIN_RESPONSE_CODE + " and " + MAX_RESPONSE_CODE);
        }
    }
    
    private void validateResponseMessage(String responseMessage, List<String> warnings) {
        if (responseMessage == null || responseMessage.trim().isEmpty()) {
            warnings.add("Response message is not provided, default message will be used");
        } else if (responseMessage.length() > 500) {
            warnings.add("Response message is long (over 500 characters)");
        }
    }
    
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        
        private ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors != null ? errors : new ArrayList<>();
            this.warnings = warnings != null ? warnings : new ArrayList<>();
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }
        
        public static ValidationResult success(List<String> warnings) {
            return new ValidationResult(true, null, warnings);
        }
        
        public static ValidationResult fail(String error) {
            List<String> errors = new ArrayList<>();
            errors.add(error);
            return new ValidationResult(false, errors, null);
        }
        
        public static ValidationResult fail(List<String> errors) {
            return new ValidationResult(false, errors, null);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public String getErrorsAsString() {
            return String.join("; ", errors);
        }
        
        public String getWarningsAsString() {
            return String.join("; ", warnings);
        }
    }
}