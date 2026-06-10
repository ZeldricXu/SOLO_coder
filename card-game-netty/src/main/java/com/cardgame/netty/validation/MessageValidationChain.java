package com.cardgame.netty.validation;

import com.cardgame.common.protocol.GameMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class MessageValidationChain {

    @Getter
    private final List<IMessageValidator> validators = new ArrayList<>();

    public MessageValidationChain() {
        addValidator(new LengthValidator());
        addValidator(new RequiredFieldValidator());
        addValidator(new VersionCompatibilityValidator());
    }

    public void addValidator(IMessageValidator validator) {
        validators.add(validator);
        validators.sort(Comparator.comparingInt(IMessageValidator::getOrder));
        log.info("Added validator: {} (order: {})", validator.getName(), validator.getOrder());
    }

    public ValidationResult validate(GameMessage message) {
        ValidationResult result = ValidationResult.success();

        for (IMessageValidator validator : validators) {
            try {
                ValidationResult validatorResult = validator.validate(message);
                if (!validatorResult.isValid()) {
                    result.setValid(false);
                    result.setErrorCode(validatorResult.getErrorCode());
                    result.getErrors().addAll(validatorResult.getErrors());
                    log.debug("Validator {} failed for message {}: {}",
                            validator.getName(),
                            message != null ? message.getType() : "null",
                            validatorResult.getFirstError());
                    break;
                }
            } catch (Exception e) {
                log.error("Error in validator {}: {}", validator.getName(), e.getMessage(), e);
                result.setValid(false);
                result.setErrorCode(500);
                result.addError("Validation error: " + e.getMessage());
                break;
            }
        }

        if (result.isValid()) {
            log.debug("Message {} passed all validations", message != null ? message.getType() : "null");
        } else {
            log.warn("Message {} validation failed: {}",
                    message != null ? message.getType() : "null",
                    result.getFirstError());
        }

        return result;
    }

    public int getValidatorCount() {
        return validators.size();
    }
}
