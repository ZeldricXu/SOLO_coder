package com.datapipeline.common.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValidationError extends BusinessException {

    private final List<FieldError> fieldErrors = new ArrayList<>();

    public ValidationError(String message) {
        super(422, message);
    }

    public ValidationError addError(String field, String message) {
        this.fieldErrors.add(new FieldError(field, message));
        return this;
    }

    public List<FieldError> getFieldErrors() {
        return new ArrayList<>(fieldErrors);
    }

    public record FieldError(String field, String message) {}

}
