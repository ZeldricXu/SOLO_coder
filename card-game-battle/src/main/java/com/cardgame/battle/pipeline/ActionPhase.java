package com.cardgame.battle.pipeline;

import com.cardgame.battle.engine.EffectProcessor;
import com.cardgame.battle.engine.TimelineEngine;
import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.deck.DeckManager;
import com.cardgame.common.service.BattleLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;

@Slf4j
@RequiredArgsConstructor
public class ActionPhase extends BattlePhase {

    private final TimelineEngine timelineEngine;
    private final DeckManager deckManager;
    private final EffectProcessor effectProcessor;
    private final BiFunction<Enemy, BattleContext, BattleAction> enemyActionExecutor;
    private final BattleLogService battleLogService;

    @Override
    public void execute(BattleContext context) throws Exception {
        log.debug("Executing ActionPhase for battle {}", context.getBattleId());

        if (!shouldContinue(context)) {
            return;
        }

        var currentActor = timelineEngine.getCurrentActor(context);
        if (currentActor == null) {
            fireNext(context);
            return;
        }

        GameCharacter character = context.getCharacter(currentActor.getCharacterId());
        if (character == null || !character.isAlive()) {
            fireNext(context);
            return;
        }

        if (currentActor.isPlayer()) {
            if (context.hasPendingAction()) {
                executePlayerAction(context, (Player) character);
            }
        } else {
            executeEnemyAction(context, (Enemy) character);
        }

        fireNext(context);
    }

    private void executePlayerAction(BattleContext context, Player player) {
        var pendingAction = context.getPendingAction();
        if (pendingAction == null) {
            return;
        }

        String cardId = pendingAction.getCardId();
        List<String> targetIds = pendingAction.getTargetIds() != null 
                ? pendingAction.getTargetIds() 
                : new ArrayList<>();

        Card card = deckManager.findCardInHand(player, cardId);
        if (card == null) {
            log.warn("Card {} not found in player {}'s hand", cardId, player.getPlayerId());
            return;
        }

        if (player.getCurrentEnergy() < card.getCurrentCost()) {
            log.warn("Player {} has insufficient energy for card {}", player.getPlayerId(), card.getName());
            return;
        }

        BattleAction action = BattleAction.builder()
                .actionId(IdGenerator.generateUUID())
                .turn(context.getCurrentTurn())
                .round(context.getCurrentRound())
                .actorId(player.getPlayerId())
                .isPlayerAction(true)
                .actionType("PLAY_CARD")
                .cardUsed(card)
                .targetIds(targetIds)
                .targetId(!targetIds.isEmpty() ? targetIds.get(0) : null)
                .energySpent(card.getCurrentCost())
                .timestamp(System.currentTimeMillis())
                .buffsApplied(new HashMap<>())
                .buffsRemoved(new HashMap<>())
                .build();

        if (!deckManager.playCard(player, cardId, targetIds)) {
            log.warn("Failed to play card {} for player {}", card.getName(), player.getPlayerId());
            return;
        }

        effectProcessor.processCardEffects(context, player, card, targetIds, action);

        context.addAction(action);
        if (battleLogService != null) {
            battleLogService.logAction(context.getBattleId(), action);
        }

        context.setPendingAction(null);
        context.setLastAction(action);

        log.debug("Player {} played card {} in battle {}", player.getName(), card.getName(), context.getBattleId());
    }

    private void executeEnemyAction(BattleContext context, Enemy enemy) {
        if (enemyActionExecutor == null) {
            log.warn("No enemy action executor configured");
            return;
        }

        BattleAction action = enemyActionExecutor.apply(enemy, context);
        if (action != null) {
            action.setTurn(context.getCurrentTurn());
            action.setRound(context.getCurrentRound());
            context.addAction(action);
            if (battleLogService != null) {
                battleLogService.logAction(context.getBattleId(), action);
            }
            context.setLastAction(action);
            log.debug("Enemy {} executed action in battle {}", enemy.getName(), context.getBattleId());
        }
    }
}
