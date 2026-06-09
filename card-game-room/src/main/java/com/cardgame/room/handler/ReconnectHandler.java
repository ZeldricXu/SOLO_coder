package com.cardgame.room.handler;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.protocol.request.ReconnectRequest;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import com.cardgame.room.service.RoomSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReconnectHandler implements MessageHandler {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private RoomSyncService roomSyncService;

    @Autowired
    private ChannelManager channelManager;

    @Override
    public MessageType getType() {
        return MessageType.RECONNECT_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        ReconnectRequest reconnectRequest = JsonUtils.fromJson(request.getData(), ReconnectRequest.class);
        if (reconnectRequest == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Invalid request", null));
            return;
        }

        String playerId = findPlayerIdByAccountId(reconnectRequest.getAccountId());
        if (playerId == null) {
            playerId = com.cardgame.common.utils.IdGenerator.generatePlayerId();
        }

        String roomId = reconnectRequest.getRoomId();
        if (roomId == null) {
            roomId = redisRoomStateManager.getPlayerRoomId(playerId);
        }

        if (roomId == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 404, "No active game found", null));
            return;
        }

        boolean reconnected = roomManager.setPlayerReconnected(playerId, roomId);

        if (!reconnected) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 408, "Reconnect timeout", null));
            return;
        }

        channelManager.bindPlayer(playerId, session);
        session.setAccountId(reconnectRequest.getAccountId());
        session.setRoomId(roomId);
        redisRoomStateManager.setPlayerOnline(playerId, true);

        Room room = roomManager.getRoom(roomId);
        redisRoomStateManager.saveRoom(room);

        log.info("Player {} reconnected to room {}", playerId, roomId);

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(room))
        );

        roomSyncService.syncRoomStatus(roomId);
    }

    private String findPlayerIdByAccountId(String accountId) {
        return null;
    }
}
