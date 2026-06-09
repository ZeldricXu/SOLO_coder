package com.cardgame.save.entity;

import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Player;
import com.cardgame.map.entity.GameMap;
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
public class GameSave {
    private String saveId;
    private String roomId;
    private String hostPlayerId;
    @Builder.Default
    private List<String> playerIds = new ArrayList<>();
    @Builder.Default
    private Map<String, Player> playerStates = new HashMap<>();
    @Builder.Default
    private Map<String, List<Card>> playerDecks = new HashMap<>();
    private GameMap gameMap;
    private int currentFloor;
    private int score;
    private int gold;
    private long seed;
    private long createdAt;
    private long updatedAt;
    private long playTimeSeconds;
    @Builder.Default
    private Map<String, Object> progressData = new HashMap<>();
    private boolean locked;
    private String lockedBy;
    private long lockedAt;
    private boolean completed;
    private boolean victory;
    private String difficulty;
    private String version;

    public void addPlayer(Player player, List<Card> deck) {
        if (!playerIds.contains(player.getPlayerId())) {
            playerIds.add(player.getPlayerId());
        }
        playerStates.put(player.getPlayerId(), player);
        playerDecks.put(player.getPlayerId(), new ArrayList<>(deck));
    }

    public void updatePlayer(Player player) {
        playerStates.put(player.getPlayerId(), player);
    }

    public Player getPlayer(String playerId) {
        return playerStates.get(playerId);
    }

    public List<Card> getPlayerDeck(String playerId) {
        return playerDecks.get(playerId);
    }

    public boolean isLocked() {
        if (!locked) return false;
        long lockDuration = System.currentTimeMillis() - lockedAt;
        return lockDuration < 300000;
    }

    public void lock(String playerId) {
        this.locked = true;
        this.lockedBy = playerId;
        this.lockedAt = System.currentTimeMillis();
    }

    public void unlock() {
        this.locked = false;
        this.lockedBy = null;
        this.lockedAt = 0;
    }

    public void incrementPlayTime(long seconds) {
        this.playTimeSeconds += seconds;
    }

    public void addScore(int points) {
        this.score += points;
    }

    public void addGold(int amount) {
        this.gold += amount;
    }

    public void spendGold(int amount) {
        this.gold = Math.max(0, this.gold - amount);
    }
}
