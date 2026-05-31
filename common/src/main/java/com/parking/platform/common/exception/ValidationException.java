package com.parking.platform.common.exception;

import java.util.ArrayList;
import java.util.List;

public class ValidationException extends BusinessException {

    private final List<String> details;

    public ValidationException(String message) {
        super(422, message);
        this.details = new ArrayList<>();
    }

    public ValidationException(String message, List<String> details) {
        super(422, message);
        this.details = details;
    }

    public List<String> getDetails() {
        return details;
    }

    public void addDetail(String detail) {
        this.details.add(detail);
    }
}
