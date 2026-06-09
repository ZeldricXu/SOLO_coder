package com.cardgame.room.entity;

import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.RoomStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {
    private String roomId;
    private String roomName;
    private String ownerId;
    private String inviteCode;
    private String password;
    private boolean privateRoom;
    private int maxPlayers;
    private RoomStatus status;
    @Builder.Default
    private List<Player> players = new ArrayList<>();
    @Builder.Default
    private Map<String, Player> playerMap = new HashMap<>();
    @Builder.Default
    private Map<String, Long> disconnectTimes = new HashMap<>();
    private String gameId;
    private int currentFloor;
    private long createTime;
    private long lastUpdateTime;
    private long matchStartTime;
    private int reconnectTimeoutSeconds;
    private long mapSeed;

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public int getOnlinePlayerCount() {
        return (int) players.stream().filter(Player::isOnline).count();
    }

    public boolean addPlayer(Player player) {
        if (isFull()) {
            return false;
        }
        player.setRoomId(roomId);
        player.setPositionIndex(players.size());
        player.setOnline(true);
        players.add(player);
        playerMap.put(player.getPlayerId(), player);
        lastUpdateTime = System.currentTimeMillis();
        return true;
    }

    public void removePlayer(String playerId) {
        Player player = playerMap.remove(playerId);
        if (player != null) {
            players.remove(player);
            player.setRoomId(null);
            player.setOnline(false);
        }
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setPositionIndex(i);
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    public Player getPlayer(String playerId) {
        return playerMap.get(playerId);
    }

    public void setPlayerDisconnected(String playerId) {
        Player player = playerMap.get(playerId);
        if (player != null) {
            player.setOnline(false);
            disconnectTimes.put(playerId, System.currentTimeMillis());
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    public boolean setPlayerReconnected(String playerId) {
        Player player = playerMap.get(playerId);
        if (player != null) {
            long disconnectTime = disconnectTimes.getOrDefault(playerId, 0L);
            if (System.currentTimeMillis() - disconnectTime < reconnectTimeoutSeconds * 1000L) {
                player.setOnline(true);
                player.setLastHeartbeat(System.currentTimeMillis());
                disconnectTimes.remove(playerId);
                lastUpdateTime = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    public boolean isAllReady() {
        if (players.isEmpty()) {
            return false;
        }
        return players.stream().allMatch(p -> !p.isOnline() || p.isReady());
    }

    public boolean isAllOnline() {
        return players.stream().allMatch(Player::isOnline);
    }

    public void setPlayerReady(String playerId, boolean ready) {
        Player player = playerMap.get(playerId);
        if (player != null) {
            player.setReady(ready);
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    public void resetReadyStatus() {
        players.forEach(p -> p.setReady(false));
    }

    public boolean hasDisconnectedPlayers() {
        return !disconnectTimes.isEmpty();
    }

    public void cleanupExpiredDisconnects() {
        long now = System.currentTimeMillis();
        disconnectTimes.entrySet().removeIf(entry -> {
            boolean expired = now - entry.getValue() > reconnectTimeoutSeconds * 1000L;
            if (expired) {
                removePlayer(entry.getKey());
            }
            return expired;
        });
    }
}
