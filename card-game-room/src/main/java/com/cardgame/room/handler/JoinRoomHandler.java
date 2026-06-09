package com.cardgame.room.handler;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.protocol.request.JoinRoomRequest;
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
public class JoinRoomHandler implements MessageHandler {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private RoomSyncService roomSyncService;

    @Autowired
    private ChannelManager channelManager;

    @Autowired
    private GameConfig gameConfig;

    @Override
    public MessageType getType() {
        return MessageType.JOIN_ROOM_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        JoinRoomRequest joinRequest = JsonUtils.fromJson(request.getData(), JoinRoomRequest.class);
        if (joinRequest == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Invalid request", null));
            return;
        }

        String playerId = session.getPlayerId();

        Room room = null;
        if (joinRequest.getRoomId() != null) {
            room = roomManager.getRoom(joinRequest.getRoomId());
        } else if (joinRequest.getInviteCode() != null) {
            room = roomManager.getRoomByInviteCode(joinRequest.getInviteCode());
        }

        if (room == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 404, "Room not found", null));
            return;
        }

        Player player = createPlayer(playerId, session, joinRequest);
        boolean success = roomManager.joinRoom(room.getRoomId(), player, joinRequest.getPassword());

        if (!success) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Failed to join room", null));
            return;
        }

        channelManager.updateRoomId(playerId, room.getRoomId());
        redisRoomStateManager.saveRoom(room);

        log.info("Player {} joined room {}", playerId, room.getRoomId());

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(room))
        );

        roomSyncService.syncRoomStatus(room.getRoomId());
    }

    private Player createPlayer(String playerId, PlayerSession session, JoinRoomRequest request) {
        Player player = Player.builder()
                .playerId(playerId)
                .accountId(session.getAccountId())
                .name("Player-" + playerId)
                .playerClass(request.getPlayerClass())
                .maxHp(gameConfig.getBasePlayerHp())
                .currentHp(gameConfig.getBasePlayerHp())
                .baseSpeed(gameConfig.getBasePlayerSpeed())
                .speed(gameConfig.getBasePlayerSpeed())
                .maxEnergy(gameConfig.getDefaultMaxEnergy())
                .currentEnergy(gameConfig.getDefaultMaxEnergy())
                .handLimit(gameConfig.getMaxHandSize())
                .gold(100)
                .floor(1)
                .online(true)
                .lastHeartbeat(System.currentTimeMillis())
                .build();
        player.setId(playerId);
        return player;
    }
}
