package com.cardgame.room.service;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RoomManager;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RoomSyncService {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private ChannelManager channelManager;

    public void syncRoomStatus(String roomId) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            return;
        }

        String roomData = JsonUtils.toJson(room);
        GameMessage message = GameMessage.createPush(
                MessageType.ROOM_STATUS_SYNC,
                roomId,
                null,
                roomData
        );

        broadcastToRoom(roomId, message);
    }

    public void syncToPlayer(String roomId, String playerId, GameMessage message) {
        Channel channel = channelManager.getChannelByPlayerId(playerId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        }
    }

    public void broadcastToRoom(String roomId, GameMessage message) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            return;
        }

        for (com.cardgame.common.entity.Player player : room.getPlayers()) {
            if (player.isOnline()) {
                Channel channel = channelManager.getChannelByPlayerId(player.getPlayerId());
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(message);
                }
            }
        }
    }

    public void broadcastToOthers(String roomId, String excludePlayerId, GameMessage message) {
        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            return;
        }

        for (com.cardgame.common.entity.Player player : room.getPlayers()) {
            if (player.isOnline() && !player.getPlayerId().equals(excludePlayerId)) {
                Channel channel = channelManager.getChannelByPlayerId(player.getPlayerId());
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(message);
                }
            }
        }
    }

    public void sendRoomStatus(String roomId, String playerId) {
        Room room = roomManager.getRoom(roomId);
        if (room != null) {
            String roomData = JsonUtils.toJson(room);
            GameMessage message = GameMessage.createPush(
                    MessageType.ROOM_STATUS_SYNC,
                    roomId,
                    playerId,
                    roomData
            );
            syncToPlayer(roomId, playerId, message);
        }
    }
}
