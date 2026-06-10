package com.cardgame.netty.validation;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class VersionCompatibilityValidator implements IMessageValidator {

    private static final int CURRENT_PROTOCOL_VERSION = 1;
    private static final int MIN_SUPPORTED_VERSION = 1;

    private final Map<String, Integer> clientVersions = new ConcurrentHashMap<>();
    private final Set<MessageType> deprecatedMessages = EnumSet.noneOf(MessageType.class);
    private final Set<MessageType> betaMessages = EnumSet.noneOf(MessageType.class);

    public VersionCompatibilityValidator() {
        initializeDeprecatedAndBeta();
    }

    private void initializeDeprecatedAndBeta() {
    }

    @Override
    public ValidationResult validate(GameMessage message) {
        if (message == null) {
            return ValidationResult.failure(400, "Message is null");
        }

        if (message.getType() == null) {
            return ValidationResult.failure(400, "Message type is required");
        }

        if (deprecatedMessages.contains(message.getType())) {
            log.warn("Message type {} is deprecated", message.getType());
        }

        if (betaMessages.contains(message.getType())) {
            log.debug("Message type {} is in beta", message.getType());
        }

        int clientVersion = extractClientVersion(message);
        if (clientVersion > 0) {
            if (clientVersion < MIN_SUPPORTED_VERSION) {
                return ValidationResult.failure(426,
                        "Client version " + clientVersion + " is too old. Minimum supported: " + MIN_SUPPORTED_VERSION);
            }
            if (clientVersion > CURRENT_PROTOCOL_VERSION + 1) {
                log.warn("Client version {} is newer than server version {}", clientVersion, CURRENT_PROTOCOL_VERSION);
            }
        }

        if (message.getData() != null && !message.getData().isEmpty()) {
            try {
                JsonUtils.fromJson(message.getData(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse message data as JSON for type {}", message.getType());
                return ValidationResult.failure(400, "Invalid JSON format in message data");
            }
        }

        return ValidationResult.success();
    }

    private int extractClientVersion(GameMessage message) {
        if (message.getData() != null && !message.getData().isEmpty()) {
            try {
                Map<String, Object> data = JsonUtils.fromJson(message.getData(), Map.class);
                if (data != null && data.containsKey("protocolVersion")) {
                    Object version = data.get("protocolVersion");
                    if (version instanceof Number) {
                        return ((Number) version).intValue();
                    } else if (version instanceof String) {
                        return Integer.parseInt((String) version);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not extract protocol version from message", e);
            }
        }
        return 0;
    }

    public void setClientVersion(String playerId, int version) {
        clientVersions.put(playerId, version);
    }

    public int getClientVersion(String playerId) {
        return clientVersions.getOrDefault(playerId, CURRENT_PROTOCOL_VERSION);
    }

    @Override
    public int getOrder() {
        return 30;
    }
}
