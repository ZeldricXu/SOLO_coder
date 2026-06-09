package com.cardgame.room.manager;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.room.entity.Room;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RoomManager {

    private final Map<String, Room> roomMap = new ConcurrentHashMap<>();
    private final Map<String, String> inviteCodeToRoomId = new ConcurrentHashMap<>();

    @Autowired
    private GameConfig gameConfig;

    public Room createRoom(String roomName, String ownerId, int maxPlayers, boolean privateRoom, String password) {
        String roomId = IdGenerator.generateRoomId();
        String inviteCode = IdGenerator.generateInviteCode();

        Room room = Room.builder()
                .roomId(roomId)
                .roomName(roomName)
                .ownerId(ownerId)
                .inviteCode(inviteCode)
                .password(password)
                .privateRoom(privateRoom)
                .maxPlayers(Math.min(maxPlayers, gameConfig.getMaxPlayersPerRoom()))
                .status(RoomStatus.WAITING)
                .createTime(System.currentTimeMillis())
                .lastUpdateTime(System.currentTimeMillis())
                .reconnectTimeoutSeconds(gameConfig.getReconnectTimeoutSeconds())
                .mapSeed(System.currentTimeMillis())
                .build();

        roomMap.put(roomId, room);
        inviteCodeToRoomId.put(inviteCode, roomId);
        log.info("Created room: {} with invite code: {}", roomId, inviteCode);
        return room;
    }

    public Room getRoom(String roomId) {
        return roomMap.get(roomId);
    }

    public Room getRoomByInviteCode(String inviteCode) {
        String roomId = inviteCodeToRoomId.get(inviteCode);
        return roomId != null ? roomMap.get(roomId) : null;
    }

    public void removeRoom(String roomId) {
        Room room = roomMap.remove(roomId);
        if (room != null) {
            inviteCodeToRoomId.remove(room.getInviteCode());
            log.info("Removed room: {}", roomId);
        }
    }

    public boolean joinRoom(String roomId, Player player, String password) {
        Room room = roomMap.get(roomId);
        if (room == null) {
            return false;
        }
        if (room.isPrivateRoom() && !room.getPassword().equals(password)) {
            return false;
        }
        if (room.isFull()) {
            return false;
        }
        if (room.getStatus() != RoomStatus.WAITING) {
            return false;
        }
        return room.addPlayer(player);
    }

    public void updateRoomStatus(String roomId, RoomStatus status) {
        Room room = roomMap.get(roomId);
        if (room != null) {
            room.setStatus(status);
            room.setLastUpdateTime(System.currentTimeMillis());
        }
    }

    public int getActiveRoomCount() {
        return roomMap.size();
    }

    public void cleanupEmptyRooms() {
        roomMap.values().removeIf(room -> {
            room.cleanupExpiredDisconnects();
            if (room.isEmpty() && System.currentTimeMillis() - room.getCreateTime() > 60000) {
                inviteCodeToRoomId.remove(room.getInviteCode());
                log.info("Cleaned up empty room: {}", room.getRoomId());
                return true;
            }
            return false;
        });
    }

    public void setPlayerDisconnected(String playerId) {
        for (Room room : roomMap.values()) {
            if (room.getPlayer(playerId) != null) {
                room.setPlayerDisconnected(playerId);
                break;
            }
        }
    }

    public boolean setPlayerReconnected(String playerId, String roomId) {
        Room room = roomMap.get(roomId);
        if (room != null) {
            return room.setPlayerReconnected(playerId);
        }
        return false;
    }
}
