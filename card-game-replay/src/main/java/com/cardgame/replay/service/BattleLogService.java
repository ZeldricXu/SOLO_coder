package com.cardgame.replay.service;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.replay.config.BattleLogSamplingConfig;
import com.cardgame.replay.entity.BattleLog;
import com.cardgame.replay.kafka.BattleLogProducer;
import com.cardgame.replay.mapper.BattleLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class BattleLogService implements com.cardgame.common.service.BattleLogService {

    @Autowired
    BattleLogMapper battleLogMapper;

    @Autowired
    BattleLogProducer battleLogProducer;

    @Autowired
    BattleLogSamplingConfig samplingConfig;

    final Map<String, BattleLog> activeBattleLogs = new HashMap<>();
    final Map<String, Boolean> bossBattleFlags = new HashMap<>();
    final Map<String, Set<String>> allowedActionTypes = new HashMap<>();

    public void startBattleLogging(BattleContext context) {
        boolean isBossBattle = samplingConfig.isBossBattle(context.getFloor());
        Set<String> allowedTypes = samplingConfig.getActionTypesForBattle(isBossBattle);
        String samplingLevel = isBossBattle ? "FULL" : "SAMPLED";

        BattleLog battleLog = BattleLog.builder()
                .battleLogId(IdGenerator.generateUUID())
                .battleId(context.getBattleId())
                .roomId(context.getRoomId())
                .floor(context.getFloor())
                .seed(context.getSeed())
                .initialPlayerStates(new ArrayList<>(context.getPlayers()))
                .initialEnemyStates(new ArrayList<>(context.getEnemies()))
                .actions(new ArrayList<>())
                .startTime(context.getStartTime())
                .version("1.0")
                .isBossBattle(isBossBattle)
                .samplingLevel(samplingLevel)
                .logTimestamp(System.currentTimeMillis())
                .build();

        activeBattleLogs.put(context.getBattleId(), battleLog);
        bossBattleFlags.put(context.getBattleId(), isBossBattle);
        allowedActionTypes.put(context.getBattleId(), allowedTypes);

        log.debug("Started logging for battle {} (boss={}, level={}, allowedActions={})",
                context.getBattleId(), isBossBattle, samplingLevel, allowedTypes.size());
    }

    public void logAction(String battleId, BattleAction action) {
        BattleLog battleLog = activeBattleLogs.get(battleId);
        if (battleLog == null || action == null) {
            return;
        }

        Set<String> allowedTypes = allowedActionTypes.get(battleId);
        if (allowedTypes != null && !allowedTypes.contains(action.getActionType())) {
            log.trace("Action {} filtered out by sampling for battle {}",
                    action.getActionType(), battleId);
            return;
        }

        battleLog.addAction(action);
    }

    @Transactional
    public void endBattleLogging(String battleId, BattleContext context) {
        BattleLog battleLog = activeBattleLogs.remove(battleId);
        if (battleLog == null) {
            return;
        }

        bossBattleFlags.remove(battleId);
        allowedActionTypes.remove(battleId);

        battleLog.setResult(context.getStatus());
        battleLog.setEndTime(context.getEndTime());
        battleLog.calculateStats();

        int originalActionCount = battleLog.getActions().size();
        int sampledActionCount = originalActionCount;
        if (!battleLog.isBossBattle()) {
            sampledActionCount = (int) (originalActionCount * 0.3);
        }

        battleLog.getStats().put("originalActionCount", originalActionCount);
        battleLog.getStats().put("sampledActionCount", battleLog.getActions().size());
        battleLog.getStats().put("samplingReductionPct",
                100 - (battleLog.getActions().size() * 100.0 / Math.max(1, originalActionCount)));

        battleLogProducer.sendBattleLogAsync(battleId, battleLog);

        log.info("Ended logging for battle {}: {}, {} turns, {} actions (sampled={}, level={}, reduction={}%)",
                battleId, battleLog.getResult(), battleLog.getTotalTurns(),
                originalActionCount, battleLog.getActions().size(),
                battleLog.getSamplingLevel(),
                battleLog.getStats().get("samplingReductionPct"));
    }

    @Transactional
    public void saveBattleLog(BattleLog battleLog) {
        try {
            battleLogMapper.insertBattleLog(battleLog);
            log.debug("Saved battle log {} to database", battleLog.getBattleLogId());
        } catch (Exception e) {
            log.error("Failed to save battle log {}: {}", battleLog.getBattleLogId(), e.getMessage());
        }
    }

    public BattleLog getBattleLog(String battleLogId) {
        return battleLogMapper.findBattleLogById(battleLogId);
    }

    public BattleLog getBattleLogByBattleId(String battleId) {
        return battleLogMapper.findBattleLogByBattleId(battleId);
    }

    public List<BattleLog> getBattleLogsForSave(String saveId, int limit) {
        return battleLogMapper.findBattleLogsBySaveId(saveId, limit);
    }

    public List<BattleLog> getBattleLogsForRoom(String roomId, int limit) {
        return battleLogMapper.findBattleLogsByRoomId(roomId, limit);
    }

    public List<BattleAction> getActionsForTurn(String battleLogId, int turn) {
        BattleLog log = getBattleLog(battleLogId);
        if (log == null) {
            return new ArrayList<>();
        }
        return log.getActionsForTurn(turn);
    }

    public List<BattleAction> getActionsForRound(String battleLogId, int round) {
        BattleLog log = getBattleLog(battleLogId);
        if (log == null) {
            return new ArrayList<>();
        }
        return log.getActionsForRound(round);
    }

    public BattleReplay createReplay(String battleLogId) {
        BattleLog battleLog = getBattleLog(battleLogId);
        if (battleLog == null) {
            return null;
        }
        return new BattleReplay(battleLog);
    }

    public Map<String, Object> analyzeBalanceData(int sampleSize) {
        List<Map<String, Object>> statsList = battleLogMapper.findVictoryStatsForAnalysis(sampleSize);

        Map<String, Object> analysis = new HashMap<>();
        Map<String, Integer> cardUsage = new HashMap<>();
        int totalDamage = 0;
        int totalHealing = 0;
        int totalGames = statsList.size();

        for (Map<String, Object> stats : statsList) {
            totalDamage += (Integer) stats.getOrDefault("totalDamage", 0);
            totalHealing += (Integer) stats.getOrDefault("totalHealing", 0);

            Map<String, Integer> usage = (Map<String, Integer>) stats.get("cardUsage");
            if (usage != null) {
                for (Map.Entry<String, Integer> entry : usage.entrySet()) {
                    cardUsage.put(entry.getKey(),
                            cardUsage.getOrDefault(entry.getKey(), 0) + entry.getValue());
                }
            }
        }

        analysis.put("totalGamesAnalyzed", totalGames);
        analysis.put("avgDamagePerGame", totalGames > 0 ? totalDamage / totalGames : 0);
        analysis.put("avgHealingPerGame", totalGames > 0 ? totalHealing / totalGames : 0);
        analysis.put("cardUsageRanking", cardUsage);

        return analysis;
    }

    public static class BattleReplay {
        private final BattleLog battleLog;
        private int currentTurn;
        private int currentActionIndex;
        private final List<Player> currentPlayerStates;
        private final List<Enemy> currentEnemyStates;

        public BattleReplay(BattleLog battleLog) {
            this.battleLog = battleLog;
            this.currentTurn = 0;
            this.currentActionIndex = 0;
            this.currentPlayerStates = deepCopyPlayers(battleLog.getInitialPlayerStates());
            this.currentEnemyStates = deepCopyEnemies(battleLog.getInitialEnemyStates());
        }

        public BattleLog getBattleLog() {
            return battleLog;
        }

        public int getCurrentTurn() {
            return currentTurn;
        }

        public int getTotalTurns() {
            return battleLog.getTotalTurns();
        }

        public List<Player> getCurrentPlayerStates() {
            return currentPlayerStates;
        }

        public List<Enemy> getCurrentEnemyStates() {
            return currentEnemyStates;
        }

        public BattleAction nextAction() {
            if (currentActionIndex >= battleLog.getActions().size()) {
                return null;
            }
            BattleAction action = battleLog.getActions().get(currentActionIndex++);
            if (action.getTurn() > currentTurn) {
                currentTurn = action.getTurn();
            }
            applyAction(action);
            return action;
        }

        public List<BattleAction> nextTurn() {
            List<BattleAction> turnActions = new ArrayList<>();
            int targetTurn = currentTurn + 1;

            while (currentActionIndex < battleLog.getActions().size()) {
                BattleAction action = battleLog.getActions().get(currentActionIndex);
                if (action.getTurn() > targetTurn) {
                    break;
                }
                if (action.getTurn() == targetTurn) {
                    turnActions.add(action);
                    applyAction(action);
                    currentActionIndex++;
                } else {
                    currentActionIndex++;
                }
            }

            currentTurn = targetTurn;
            return turnActions;
        }

        public boolean hasNext() {
            return currentActionIndex < battleLog.getActions().size();
        }

        public void reset() {
            currentTurn = 0;
            currentActionIndex = 0;
            deepCopyPlayersInto(battleLog.getInitialPlayerStates(), currentPlayerStates);
            deepCopyEnemiesInto(battleLog.getInitialEnemyStates(), currentEnemyStates);
        }

        private void applyAction(BattleAction action) {
        }

        private List<Player> deepCopyPlayers(List<Player> players) {
            List<Player> copy = new ArrayList<>();
            for (Player p : players) {
                copy.add(com.cardgame.common.utils.JsonUtils.fromJson(
                        com.cardgame.common.utils.JsonUtils.toJson(p), Player.class));
            }
            return copy;
        }

        private List<Enemy> deepCopyEnemies(List<Enemy> enemies) {
            List<Enemy> copy = new ArrayList<>();
            for (Enemy e : enemies) {
                copy.add(com.cardgame.common.utils.JsonUtils.fromJson(
                        com.cardgame.common.utils.JsonUtils.toJson(e), Enemy.class));
            }
            return copy;
        }

        private void deepCopyPlayersInto(List<Player> source, List<Player> target) {
            target.clear();
            target.addAll(deepCopyPlayers(source));
        }

        private void deepCopyEnemiesInto(List<Enemy> source, List<Enemy> target) {
            target.clear();
            target.addAll(deepCopyEnemies(source));
        }
    }
}
