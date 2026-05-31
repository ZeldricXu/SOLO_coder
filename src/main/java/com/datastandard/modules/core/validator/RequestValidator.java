package com.datastandard.modules.core.validator;

import com.datastandard.common.exception.ValidationException;
import com.datastandard.modules.core.dto.StandardizationConfig;
import com.datastandard.modules.core.dto.TransformRequest;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RequestValidator {

    private final Validator validator;

    public RequestValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(TransformRequest request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String errorMsg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("参数校验失败");
            throw new ValidationException(422, "参数校验失败: " + errorMsg);
        }

        if (request.getConfig() != null) {
            validateConfig(request.getConfig());
        }
    }

    private void validateConfig(StandardizationConfig config) {
        var violations = validator.validate(config);
        if (!violations.isEmpty()) {
            String errorMsg = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("配置参数校验失败");
            throw new ValidationException(422, "配置参数校验失败: " + errorMsg);
        }
    }
}
