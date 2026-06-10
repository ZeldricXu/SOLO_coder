package com.cardgame.battle.pipeline;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.battle.engine.TimelineEngine;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.battle.entity.TimelineEntry;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.GameCharacter;
import com.cardgame.common.entity.Player;
import com.cardgame.deck.DeckManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
@RequiredArgsConstructor
public class PostTurnCleanupPhase extends BattlePhase {

    private final BuffSystem buffSystem;
    private final TimelineEngine timelineEngine;
    private final BiConsumer<Enemy, BattleContext> intentGenerator;
    private final DeckManager deckManager;

    @Override
    public void execute(BattleContext context) throws Exception {
        log.debug("Executing PostTurnCleanupPhase for battle {}", context.getBattleId());

        if (!shouldContinue(context)) {
            return;
        }

        TimelineEntry currentActor = timelineEngine.getCurrentActor(context);
        if (currentActor == null) {
            fireNext(context);
            return;
        }

        GameCharacter character = context.getCharacter(currentActor.getCharacterId());
        if (character == null) {
            fireNext(context);
            return;
        }

        if (character.isAlive()) {
            buffSystem.processTurnEndBuffs(character);

            if (character instanceof Enemy enemy && intentGenerator != null) {
                intentGenerator.accept(enemy, context);
            }
        }

        if (character instanceof Player player) {
            player.discardHand();
            log.debug("Player {} discarded hand at turn end", player.getName());
        }

        timelineEngine.advanceToNextActor(context);
        context.setCurrentActor(null);

        fireNext(context);
    }
}
