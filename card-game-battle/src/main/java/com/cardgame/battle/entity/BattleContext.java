package com.cardgame.battle.entity;

import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.BuffType;
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
public class BattleContext {
    private String battleId;
    private String roomId;
    private int floor;
    private BattleStatus status;
    private int currentTurn;
    private int currentRound;
    private String currentActorId;
    private PendingAction pendingAction;
    private BattleAction lastAction;
    @Builder.Default
    private List<Player> players = new ArrayList<>();
    @Builder.Default
    private List<Enemy> enemies = new ArrayList<>();
    @Builder.Default
    private List<TimelineEntry> timeline = new ArrayList<>();
    @Builder.Default
    private Map<String, GameCharacter> characterMap = new HashMap<>();
    @Builder.Default
    private List<BattleAction> actionHistory = new ArrayList<>();
    private long startTime;
    private long endTime;
    private long seed;
    private int turnTimeLimitSeconds;

    public Player getPlayer(String playerId) {
        for (Player player : players) {
            if (player.getPlayerId().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    public Enemy getEnemy(String enemyId) {
        for (Enemy enemy : enemies) {
            if (enemy.getId().equals(enemyId)) {
                return enemy;
            }
        }
        return null;
    }

    public com.cardgame.common.entity.GameCharacter getCharacter(String characterId) {
        return characterMap.get(characterId);
    }

    public List<Player> getAlivePlayers() {
        return players.stream().filter(com.cardgame.common.entity.GameCharacter::isAlive).toList();
    }

    public List<Enemy> getAliveEnemies() {
        return enemies.stream().filter(com.cardgame.common.entity.GameCharacter::isAlive).toList();
    }

    public boolean isPlayerTurn() {
        return status == BattleStatus.PLAYER_TURN;
    }

    public boolean isBattleOver() {
        return status == BattleStatus.VICTORY || status == BattleStatus.DEFEAT || status == BattleStatus.FLED;
    }

    public boolean checkVictory() {
        return getAliveEnemies().isEmpty();
    }

    public boolean checkDefeat() {
        return getAlivePlayers().isEmpty();
    }

    public void addAction(BattleAction action) {
        actionHistory.add(action);
    }

    public boolean hasPendingAction() {
        return pendingAction != null;
    }

    public void clearPendingAction() {
        this.pendingAction = null;
    }

    public void setPendingPlayCardAction(String playerId, String cardId, List<String> targetIds) {
        this.pendingAction = PendingAction.builder()
                .playerId(playerId)
                .cardId(cardId)
                .targetIds(targetIds)
                .actionType("PLAY_CARD")
                .build();
    }

    public int calculateDamage(com.cardgame.common.entity.GameCharacter attacker,
                              com.cardgame.common.entity.GameCharacter target,
                              int baseDamage) {
        int damage = baseDamage;

        int strength = attacker.getBuffStacks(BuffType.STRENGTH.name());
        damage += strength;

        if (target.hasBuff(BuffType.VULNERABLE.name())) {
            damage = (int) (damage * 1.5);
        }

        if (attacker.hasBuff(BuffType.WEAK.name())) {
            damage = (int) (damage * 0.75);
        }

        if (target.hasBuff(BuffType.FRAIL.name())) {
            damage = (int) (damage * 1.25);
        }

        int dexterity = attacker.getBuffStacks(BuffType.DEXTERITY.name());
        if (dexterity > 0) {
            damage += dexterity / 2;
        }

        int rage = attacker.getBuffStacks(BuffType.RAGE.name());
        if (rage > 0) {
            damage += rage * 2;
        }

        return Math.max(0, damage);
    }

    public int calculateBlock(com.cardgame.common.entity.GameCharacter character, int baseBlock) {
        int block = baseBlock;

        int dexterity = character.getBuffStacks(BuffType.DEXTERITY.name());
        block += dexterity;

        if (character.hasBuff(BuffType.FRAIL.name())) {
            block = (int) (block * 0.75);
        }

        return Math.max(0, block);
    }
}
