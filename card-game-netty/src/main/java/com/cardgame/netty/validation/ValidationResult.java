package com.cardgame.netty.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    private boolean valid;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    private int errorCode;

    public static ValidationResult success() {
        return ValidationResult.builder()
                .valid(true)
                .errorCode(0)
                .build();
    }

    public static ValidationResult failure(int errorCode, String error) {
        ValidationResult result = new ValidationResult();
        result.setValid(false);
        result.setErrorCode(errorCode);
        result.getErrors().add(error);
        return result;
    }

    public void addError(String error) {
        this.errors.add(error);
        this.valid = false;
    }

    public String getFirstError() {
        return errors.isEmpty() ? null : errors.get(0);
    }
}
