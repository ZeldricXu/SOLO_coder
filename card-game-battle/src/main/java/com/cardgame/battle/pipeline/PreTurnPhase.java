package com.cardgame.battle.pipeline;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.battle.engine.TimelineEngine;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.deck.DeckManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
@RequiredArgsConstructor
public class PreTurnPhase extends BattlePhase {

    private final BuffSystem buffSystem;
    private final TimelineEngine timelineEngine;
    private final DeckManager deckManager;
    private final GameConfig gameConfig;
    private final BiConsumer<Enemy, BattleContext> intentGenerator;

    @Override
    public void execute(BattleContext context) throws Exception {
        log.debug("Executing PreTurnPhase for battle {}", context.getBattleId());

        if (!shouldContinue(context)) {
            return;
        }

        var currentActor = timelineEngine.getCurrentActor(context);
        if (currentActor == null) {
            log.debug("No current actor, starting new round");
            timelineEngine.startNewRound(context);
            currentActor = timelineEngine.getCurrentActor(context);
            if (currentActor == null) {
                log.error("No actor found after starting new round");
                return;
            }
        }

        GameCharacter character = context.getCharacter(currentActor.getCharacterId());
        if (character == null) {
            log.warn("Character {} not found, advancing to next", currentActor.getCharacterId());
            timelineEngine.advanceToNextActor(context);
            fireNext(context);
            return;
        }

        if (!character.isAlive()) {
            log.debug("Character {} is dead, skipping", character.getName());
            timelineEngine.advanceToNextActor(context);
            fireNext(context);
            return;
        }

        if (currentActor.isPlayer()) {
            handlePlayerTurnStart(context, (Player) character);
        } else {
            handleEnemyTurnStart(context, (Enemy) character);
        }

        if (buffSystem.isStunned(character)) {
            log.debug("Character {} is stunned, skipping turn", character.getName());
            handleStunnedCharacter(context, character);
            timelineEngine.advanceToNextActor(context);
            fireNext(context);
            return;
        }

        context.setCurrentActorId(currentActor.getCharacterId());
        fireNext(context);
    }

    private void handlePlayerTurnStart(BattleContext context, Player player) {
        context.setStatus(BattleStatus.PLAYER_TURN);
        context.setCurrentTurn(context.getCurrentTurn() + 1);

        buffSystem.processTurnStartBuffs(player);
        player.resetBlock();
        player.resetEnergy();
        player.discardHand();
        player.drawCards(gameConfig.getDefaultDrawPerTurn());

        log.debug("Player {} turn started: turn {}, energy {}, hand size {}",
                player.getName(), context.getCurrentTurn(),
                player.getCurrentEnergy(), player.getCurrentHand().size());
    }

    private void handleEnemyTurnStart(BattleContext context, Enemy enemy) {
        context.setStatus(BattleStatus.ENEMY_TURN);
        context.setCurrentTurn(context.getCurrentTurn() + 1);

        buffSystem.processTurnStartBuffs(enemy);
        enemy.resetBlock();

        log.debug("Enemy {} turn started: turn {}", enemy.getName(), context.getCurrentTurn());
    }

    private void handleStunnedCharacter(BattleContext context, GameCharacter character) {
        buffSystem.processTurnEndBuffs(character);

        if (character instanceof Enemy enemy && intentGenerator != null) {
            intentGenerator.accept(enemy, context);
        }
    }
}
