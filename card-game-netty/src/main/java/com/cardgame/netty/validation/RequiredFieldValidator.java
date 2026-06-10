package com.cardgame.netty.validation;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class RequiredFieldValidator implements IMessageValidator {

    private final Map<MessageType, Set<String>> requiredFields = new EnumMap<>(MessageType.class);

    public RequiredFieldValidator() {
        initializeRequiredFields();
    }

    private void initializeRequiredFields() {
        Set<String> loginRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.LOGIN_REQ, loginRequired);

        Set<String> createRoomRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.CREATE_ROOM_REQ, createRoomRequired);

        Set<String> joinRoomRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.JOIN_ROOM_REQ, joinRoomRequired);

        Set<String> quickMatchRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.QUICK_MATCH_REQ, quickMatchRequired);

        Set<String> playCardRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.PLAY_CARD_REQ, playCardRequired);

        Set<String> endTurnRequired = Set.of("playerId");
        requiredFields.put(MessageType.END_TURN_REQ, endTurnRequired);

        Set<String> mapNodeSelectRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.MAP_NODE_SELECT_REQ, mapNodeSelectRequired);

        Set<String> playerReadyRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.PLAYER_READY_REQ, playerReadyRequired);

        Set<String> startGameRequired = Set.of("playerId");
        requiredFields.put(MessageType.START_GAME_REQ, startGameRequired);

        Set<String> reconnectRequired = Set.of("playerId", "data");
        requiredFields.put(MessageType.RECONNECT_REQ, reconnectRequired);
    }

    @Override
    public ValidationResult validate(GameMessage message) {
        if (message == null) {
            return ValidationResult.failure(400, "Message is null");
        }

        if (message.getType() == null) {
            return ValidationResult.failure(400, "Message type is required");
        }

        if (message.getTimestamp() <= 0) {
            return ValidationResult.failure(400, "Valid timestamp is required");
        }

        Set<String> required = requiredFields.get(message.getType());
        if (required == null) {
            return ValidationResult.success();
        }

        Set<String> missingFields = new HashSet<>();
        for (String field : required) {
            if (!hasField(message, field)) {
                missingFields.add(field);
            }
        }

        if (!missingFields.isEmpty()) {
            String error = "Missing required fields: " + String.join(", ", missingFields);
            log.warn("Validation failed for message {}: {}", message.getType(), error);
            return ValidationResult.failure(400, error);
        }

        return ValidationResult.success();
    }

    private boolean hasField(GameMessage message, String field) {
        return switch (field) {
            case "playerId" -> message.getPlayerId() != null && !message.getPlayerId().isEmpty();
            case "roomId" -> message.getRoomId() != null && !message.getRoomId().isEmpty();
            case "data" -> message.getData() != null && !message.getData().trim().isEmpty();
            case "requestId" -> message.getRequestId() != null && !message.getRequestId().isEmpty();
            default -> true;
        };
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
