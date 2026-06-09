package com.cardgame.room.handler;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class InviteCodeHandler implements MessageHandler {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Override
    public MessageType getType() {
        return MessageType.INVITE_CODE_REQ;
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

        if (!room.getOwnerId().equals(playerId)) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 403, "Not room owner", null));
            return;
        }

        String newInviteCode = com.cardgame.common.utils.IdGenerator.generateInviteCode();
        room.setInviteCode(newInviteCode);
        redisRoomStateManager.saveRoom(room);

        Map<String, String> response = new HashMap<>();
        response.put("inviteCode", newInviteCode);
        response.put("roomId", roomId);

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(response))
        );
    }
}
