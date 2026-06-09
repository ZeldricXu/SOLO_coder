package com.cardgame.common.protocol;

import com.cardgame.common.enums.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {
    private MessageType type;
    private String requestId;
    private String playerId;
    private String roomId;
    private long timestamp;
    private String data;
    private int code;
    private String message;

    public static GameMessage createResponse(GameMessage request, int code, String message, String data) {
        return GameMessage.builder()
                .type(request.getType())
                .requestId(request.getRequestId())
                .playerId(request.getPlayerId())
                .roomId(request.getRoomId())
                .timestamp(System.currentTimeMillis())
                .code(code)
                .message(message)
                .data(data)
                .build();
    }

    public static GameMessage createPush(MessageType type, String roomId, String playerId, String data) {
        return GameMessage.builder()
                .type(type)
                .requestId(java.util.UUID.randomUUID().toString())
                .playerId(playerId)
                .roomId(roomId)
                .timestamp(System.currentTimeMillis())
                .code(0)
                .data(data)
                .build();
    }
}
