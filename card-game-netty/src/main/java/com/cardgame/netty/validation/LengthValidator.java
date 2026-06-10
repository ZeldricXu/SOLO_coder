package com.cardgame.netty.validation;

import com.cardgame.common.protocol.GameMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LengthValidator implements IMessageValidator {

    private final int maxLength;
    private final int minLength;

    public LengthValidator() {
        this(10 * 1024 * 1024, 1);
    }

    @Override
    public ValidationResult validate(GameMessage message) {
        if (message == null) {
            return ValidationResult.failure(400, "Message is null");
        }

        if (message.getData() != null) {
            int dataLength = message.getData().length();
            if (dataLength > maxLength) {
                log.warn("Message data length {} exceeds maximum {}", dataLength, maxLength);
                return ValidationResult.failure(413,
                        "Message too large: " + dataLength + " bytes (max: " + maxLength + ")");
            }
            if (dataLength < minLength && message.getData().trim().isEmpty()) {
                return ValidationResult.failure(400, "Message data is empty");
            }
        }

        if (message.getRequestId() != null) {
            int requestIdLength = message.getRequestId().length();
            if (requestIdLength > 128) {
                return ValidationResult.failure(400, "RequestId too long: " + requestIdLength + " chars");
            }
        }

        if (message.getPlayerId() != null) {
            int playerIdLength = message.getPlayerId().length();
            if (playerIdLength > 64) {
                return ValidationResult.failure(400, "PlayerId too long: " + playerIdLength + " chars");
            }
        }

        if (message.getRoomId() != null) {
            int roomIdLength = message.getRoomId().length();
            if (roomIdLength > 64) {
                return ValidationResult.failure(400, "RoomId too long: " + roomIdLength + " chars");
            }
        }

        return ValidationResult.success();
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
