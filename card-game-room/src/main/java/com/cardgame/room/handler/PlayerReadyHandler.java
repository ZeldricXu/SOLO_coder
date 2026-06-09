package com.cardgame.room.handler;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import com.cardgame.room.service.RoomSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class PlayerReadyHandler implements MessageHandler {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private RoomSyncService roomSyncService;

    @Override
    public MessageType getType() {
        return MessageType.PLAYER_READY_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        String playerId = session.getPlayerId();
        String roomId = redisRoomStateManager.getPlayerRoomId(playerId);

        if (roomId == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Not in a room", null));
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 404, "Room not found", null));
            return;
        }

        Map<String, Object> data = com.cardgame.common.utils.JsonUtils.fromJson(request.getData(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        boolean ready = data != null && Boolean.TRUE.equals(data.get("ready"));

        room.setPlayerReady(playerId, ready);
        redisRoomStateManager.saveRoom(room);

        Map<String, Object> response = new HashMap<>();
        response.put("ready", ready);
        response.put("allReady", room.isAllReady());

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", com.cardgame.common.utils.JsonUtils.toJson(response))
        );

        roomSyncService.syncRoomStatus(roomId);
    }
}
