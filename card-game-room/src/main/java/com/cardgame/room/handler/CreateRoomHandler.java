package com.cardgame.room.handler;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.protocol.request.CreateRoomRequest;
import com.cardgame.common.protocol.response.CreateRoomResponse;
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
public class CreateRoomHandler implements MessageHandler {

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
        return MessageType.CREATE_ROOM_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        CreateRoomRequest createRequest = JsonUtils.fromJson(request.getData(), CreateRoomRequest.class);
        if (createRequest == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Invalid request", null));
            return;
        }

        String playerId = session.getPlayerId();

        String existingRoomId = redisRoomStateManager.getPlayerRoomId(playerId);
        if (existingRoomId != null) {
            Room existingRoom = roomManager.getRoom(existingRoomId);
            if (existingRoom != null && existingRoom.getStatus() != RoomStatus.FINISHED
                    && existingRoom.getStatus() != RoomStatus.CLOSED) {
                session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Already in a room", null));
                return;
            }
        }

        Room room = roomManager.createRoom(
                createRequest.getRoomName(),
                playerId,
                createRequest.getMaxPlayers(),
                createRequest.isPrivateRoom(),
                createRequest.getPassword()
        );

        Player player = createPlayer(playerId, session, createRequest);
        room.addPlayer(player);
        room.setOwnerId(playerId);
        room.setPlayerReady(playerId, true);
        channelManager.updateRoomId(playerId, room.getRoomId());

        redisRoomStateManager.saveRoom(room);

        CreateRoomResponse response = CreateRoomResponse.builder()
                .roomId(room.getRoomId())
                .inviteCode(room.getInviteCode())
                .build();

        log.info("Room created: {} by player: {}", room.getRoomId(), playerId);

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(response))
        );

        roomSyncService.syncRoomStatus(room.getRoomId());
    }

    private Player createPlayer(String playerId, PlayerSession session, CreateRoomRequest request) {
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
                .ready(true)
                .lastHeartbeat(System.currentTimeMillis())
                .build();
        player.setId(playerId);
        return player;
    }
}
