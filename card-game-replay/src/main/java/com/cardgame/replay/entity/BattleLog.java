package com.cardgame.replay.entity;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
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
public class BattleLog {
    private String battleLogId;
    private String battleId;
    private String roomId;
    private String saveId;
    private int floor;
    private long seed;
    @Builder.Default
    private List<Player> initialPlayerStates = new ArrayList<>();
    @Builder.Default
    private List<Enemy> initialEnemyStates = new ArrayList<>();
    @Builder.Default
    private List<BattleAction> actions = new ArrayList<>();
    @Builder.Default
    private Map<Integer, List<BattleAction>> actionsByTurn = new HashMap<>();
    @Builder.Default
    private Map<Integer, List<BattleAction>> actionsByRound = new HashMap<>();
    private BattleStatus result;
    private long startTime;
    private long endTime;
    private long durationMs;
    private int totalTurns;
    private int totalRounds;
    @Builder.Default
    private Map<String, Object> stats = new HashMap<>();
    private String version;

    public void addAction(BattleAction action) {
        actions.add(action);

        actionsByTurn.computeIfAbsent(action.getTurn(), k -> new ArrayList<>())
                .add(action);

        actionsByRound.computeIfAbsent(action.getRound(), k -> new ArrayList<>())
                .add(action);

        totalTurns = Math.max(totalTurns, action.getTurn());
        totalRounds = Math.max(totalRounds, action.getRound());
    }

    public List<BattleAction> getActionsForTurn(int turn) {
        return actionsByTurn.getOrDefault(turn, new ArrayList<>());
    }

    public List<BattleAction> getActionsForRound(int round) {
        return actionsByRound.getOrDefault(round, new ArrayList<>());
    }

    public void calculateStats() {
        int totalDamage = 0;
        int totalHealing = 0;
        int totalBlock = 0;
        int cardsPlayed = 0;

        Map<String, Integer> damageByPlayer = new HashMap<>();
        Map<String, Integer> cardUsage = new HashMap<>();

        for (BattleAction action : actions) {
            totalDamage += action.getDamageDealt();
            totalHealing += action.getHealAmount();
            totalBlock += action.getBlockGained();

            if (action.isPlayerAction() && "PLAY_CARD".equals(action.getActionType())) {
                cardsPlayed++;
                if (action.getCardUsed() != null) {
                    String cardId = action.getCardUsed().getTemplateId();
                    cardUsage.put(cardId, cardUsage.getOrDefault(cardId, 0) + 1);
                }
            }

            if (action.isPlayerAction()) {
                int currentDamage = damageByPlayer.getOrDefault(action.getActorId(), 0);
                damageByPlayer.put(action.getActorId(), currentDamage + action.getDamageDealt());
            }
        }

        stats.put("totalDamage", totalDamage);
        stats.put("totalHealing", totalHealing);
        stats.put("totalBlock", totalBlock);
        stats.put("cardsPlayed", cardsPlayed);
        stats.put("damageByPlayer", damageByPlayer);
        stats.put("cardUsage", cardUsage);
        stats.put("avgDamagePerTurn", totalTurns > 0 ? totalDamage / totalTurns : 0);
        stats.put("result", result != null ? result.name() : "UNKNOWN");
        durationMs = endTime - startTime;
        stats.put("durationMs", durationMs);
    }
}
