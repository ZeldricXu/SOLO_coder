package com.cardgame.map.service;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.map.entity.GameMap;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RoomManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MapSyncService {

    @Autowired
    private ChannelManager channelManager;

    @Autowired
    private RoomManager roomManager;

    public void syncMapUpdate(String roomId, GameMap map) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        GameMessage message = GameMessage.builder()
                .type(MessageType.MAP_UPDATE_NOTIFY)
                .roomId(roomId)
                .timestamp(System.currentTimeMillis())
                .code(0)
                .data(JsonUtils.toJson(map))
                .build();

        for (com.cardgame.common.entity.Player player : room.getPlayers()) {
            PlayerSession session = channelManager.getSession(player.getPlayerId());
            if (session != null && session.getChannel() != null && session.getChannel().isActive()) {
                session.getChannel().writeAndFlush(message);
            }
        }

        log.debug("Synced map update to room {}", roomId);
    }

    public void syncNodeReveal(String roomId, String nodeId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("nodeId", nodeId);
        data.put("revealed", true);

        GameMessage message = GameMessage.builder()
                .type(MessageType.MAP_NODE_REVEAL_NOTIFY)
                .roomId(roomId)
                .timestamp(System.currentTimeMillis())
                .code(0)
                .data(JsonUtils.toJson(data))
                .build();

        for (com.cardgame.common.entity.Player player : room.getPlayers()) {
            PlayerSession session = channelManager.getSession(player.getPlayerId());
            if (session != null && session.getChannel() != null && session.getChannel().isActive()) {
                session.getChannel().writeAndFlush(message);
            }
        }
    }
}
