package com.cardgame.battle.engine;

import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.battle.entity.TimelineEntry;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.deck.DeckManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class BattleEngine {

    private final Map<String, BattleContext> activeBattles = new ConcurrentHashMap<>();

    @Autowired
    private TimelineEngine timelineEngine;

    @Autowired
    private EffectProcessor effectProcessor;

    @Autowired
    private BuffSystem buffSystem;

    @Autowired
    private DeckManager deckManager;

    @Autowired
    private GameConfig gameConfig;

    @Autowired
    private com.cardgame.ai.EnemyAIService enemyAIService;

    public BattleContext startBattle(String roomId, int floor, List<Player> players, List<Enemy> enemies) {
        String battleId = IdGenerator.generateBattleId();

        BattleContext context = BattleContext.builder()
                .battleId(battleId)
                .roomId(roomId)
                .floor(floor)
                .status(BattleStatus.NOT_STARTED)
                .currentTurn(1)
                .currentRound(1)
                .players(new ArrayList<>(players))
                .enemies(new ArrayList<>(enemies))
                .characterMap(new HashMap<>())
                .actionHistory(new ArrayList<>())
                .startTime(System.currentTimeMillis())
                .seed(System.currentTimeMillis())
                .turnTimeLimitSeconds(60)
                .build();

        for (Player player : players) {
            deckManager.initializeStartingDeck(player);
            deckManager.prepareForBattle(player);
        }

        for (Enemy enemy : enemies) {
            enemyAIService.generateIntent(enemy, context);
        }

        timelineEngine.buildTimeline(context);
        context.setStatus(BattleStatus.PLAYER_TURN);
        activeBattles.put(battleId, context);

        log.info("Started battle {} in room {} on floor {}", battleId, roomId, floor);
        return context;
    }

    public BattleContext getBattle(String battleId) {
        return activeBattles.get(battleId);
    }

    public BattleContext getBattleByRoomId(String roomId) {
        for (BattleContext context : activeBattles.values()) {
            if (context.getRoomId().equals(roomId)) {
                return context;
            }
        }
        return null;
    }

    public BattleAction playCard(String battleId, String playerId, String cardId, List<String> targetIds) {
        BattleContext context = activeBattles.get(battleId);
        if (context == null || context.isBattleOver()) {
            return null;
        }

        Player player = context.getPlayer(playerId);
        if (player == null || !player.isAlive()) {
            return null;
        }

        TimelineEntry currentActor = timelineEngine.getCurrentActor(context);
        if (currentActor == null || !currentActor.getCharacterId().equals(playerId)) {
            return null;
        }

        Card card = deckManager.findCardInHand(player, cardId);
        if (card == null) {
            return null;
        }

        if (player.getCurrentEnergy() < card.getCurrentCost()) {
            return null;
        }

        BattleAction action = BattleAction.builder()
                .actionId(IdGenerator.generateUUID())
                .turn(context.getCurrentTurn())
                .round(context.getCurrentRound())
                .actorId(playerId)
                .isPlayerAction(true)
                .actionType("PLAY_CARD")
                .cardUsed(card)
                .targetIds(targetIds != null ? targetIds : new ArrayList<>())
                .targetId(targetIds != null && !targetIds.isEmpty() ? targetIds.get(0) : null)
                .energySpent(card.getCurrentCost())
                .timestamp(System.currentTimeMillis())
                .buffsApplied(new HashMap<>())
                .buffsRemoved(new HashMap<>())
                .build();

        if (!deckManager.playCard(player, cardId, targetIds)) {
            return null;
        }

        effectProcessor.processCardEffects(context, player, card, targetIds, action);

        context.addAction(action);
        if (battleLogService != null) {
            battleLogService.logAction(battleId, action);
        }
        checkBattleEnd(context);

        log.debug("Player {} played card {} in battle {}", playerId, card.getName(), battleId);
        return action;
    }

    public BattleAction endTurn(String battleId, String playerId) {
        BattleContext context = activeBattles.get(battleId);
        if (context == null || context.isBattleOver()) {
            return null;
        }

        Player player = context.getPlayer(playerId);
        if (player == null) {
            return null;
        }

        TimelineEntry currentActor = timelineEngine.getCurrentActor(context);
        if (currentActor == null || !currentActor.getCharacterId().equals(playerId)) {
            return null;
        }

        BattleAction action = BattleAction.builder()
                .actionId(IdGenerator.generateUUID())
                .turn(context.getCurrentTurn())
                .round(context.getCurrentRound())
                .actorId(playerId)
                .isPlayerAction(true)
                .actionType("END_TURN")
                .timestamp(System.currentTimeMillis())
                .buffsApplied(new HashMap<>())
                .buffsRemoved(new HashMap<>())
                .build();

        buffSystem.processTurnEndBuffs(player);
        player.discardHand();
        timelineEngine.advanceToNextActor(context);

        context.addAction(action);
        if (battleLogService != null) {
            battleLogService.logAction(battleId, action);
        }
        checkBattleEnd(context);

        if (!context.isBattleOver()) {
            processNextActor(context);
        }

        log.debug("Player {} ended turn in battle {}", playerId, battleId);
        return action;
    }

    private void processNextActor(BattleContext context) {
        TimelineEntry nextActor = timelineEngine.getCurrentActor(context);
        if (nextActor == null) {
            startNewRound(context);
            return;
        }

        if (nextActor.isPlayer()) {
            Player player = context.getPlayer(nextActor.getCharacterId());
            if (player != null) {
                startPlayerTurn(context, player);
            }
        } else {
            Enemy enemy = context.getEnemy(nextActor.getCharacterId());
            if (enemy != null) {
                executeEnemyTurn(context, enemy);
            }
        }
    }

    private void startPlayerTurn(BattleContext context, Player player) {
        context.setStatus(BattleStatus.PLAYER_TURN);
        context.setCurrentTurn(context.getCurrentTurn() + 1);

        buffSystem.processTurnStartBuffs(player);
        player.resetBlock();
        player.resetEnergy();
        player.discardHand();
        player.drawCards(gameConfig.getDefaultDrawPerTurn());

        if (checkBattleEnd(context)) {
            return;
        }

        log.debug("Started player {}'s turn in battle {}", player.getPlayerId(), context.getBattleId());
    }

    private void executeEnemyTurn(BattleContext context, Enemy enemy) {
        context.setStatus(BattleStatus.ENEMY_TURN);
        context.setCurrentTurn(context.getCurrentTurn() + 1);

        buffSystem.processTurnStartBuffs(enemy);
        enemy.resetBlock();

        if (!enemy.isAlive() || checkBattleEnd(context)) {
            timelineEngine.advanceToNextActor(context);
            processNextActor(context);
            return;
        }

        BattleAction action = enemyAIService.executeEnemyAction(enemy, context);
        if (action != null) {
            action.setTurn(context.getCurrentTurn());
            action.setRound(context.getCurrentRound());
            context.addAction(action);
            if (battleLogService != null) {
                battleLogService.logAction(context.getBattleId(), action);
            }
        }

        buffSystem.processTurnEndBuffs(enemy);
        enemyAIService.generateIntent(enemy, context);

        timelineEngine.advanceToNextActor(context);

        if (!checkBattleEnd(context)) {
            processNextActor(context);
        }

        log.debug("Enemy {} took turn in battle {}", enemy.getName(), context.getBattleId());
    }

    private void startNewRound(BattleContext context) {
        timelineEngine.startNewRound(context);
        log.debug("Started round {} in battle {}", context.getCurrentRound(), context.getBattleId());

        TimelineEntry firstActor = timelineEngine.getCurrentActor(context);
        if (firstActor != null) {
            if (firstActor.isPlayer()) {
                Player player = context.getPlayer(firstActor.getCharacterId());
                if (player != null) {
                    startPlayerTurn(context, player);
                }
            } else {
                Enemy enemy = context.getEnemy(firstActor.getCharacterId());
                if (enemy != null) {
                    executeEnemyTurn(context, enemy);
                }
            }
        }
    }

    private boolean checkBattleEnd(BattleContext context) {
        if (context.checkVictory()) {
            context.setStatus(BattleStatus.VICTORY);
            context.setEndTime(System.currentTimeMillis());
            log.info("Battle {} ended in victory", context.getBattleId());
            if (battleLogService != null) {
                battleLogService.endBattleLogging(context.getBattleId(), context);
            }
            cleanupBattle(context.getBattleId());
            return true;
        }

        if (context.checkDefeat()) {
            context.setStatus(BattleStatus.DEFEAT);
            context.setEndTime(System.currentTimeMillis());
            log.info("Battle {} ended in defeat", context.getBattleId());
            if (battleLogService != null) {
                battleLogService.endBattleLogging(context.getBattleId(), context);
            }
            cleanupBattle(context.getBattleId());
            return true;
        }

        return false;
    }

    public void cleanupBattle(String battleId) {
        activeBattles.remove(battleId);
    }

    public int getActiveBattleCount() {
        return activeBattles.size();
    }

    public BattleContext fleeBattle(String battleId, String playerId) {
        BattleContext context = activeBattles.get(battleId);
        if (context == null) {
            return null;
        }

        context.setStatus(BattleStatus.FLED);
        context.setEndTime(System.currentTimeMillis());
        cleanupBattle(battleId);

        log.info("Player {} fled from battle {}", playerId, battleId);
        return context;
    }
}
