package com.cardgame.battle.pipeline;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.battle.engine.EffectProcessor;
import com.cardgame.battle.engine.TimelineEngine;
import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Enemy;
import com.cardgame.deck.DeckManager;
import com.cardgame.common.service.BattleLogService;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

@Slf4j
public class BattlePipeline {

    private final BattlePhase head;
    private final BattleLogService battleLogService;

    private BattlePipeline(BattlePhase head, BattleLogService battleLogService) {
        this.head = head;
        this.battleLogService = battleLogService;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void execute(BattleContext context) throws Exception {
        log.debug("Starting battle pipeline execution for battle {}", context.getBattleId());
        head.execute(context);
        log.debug("Battle pipeline execution completed for battle {}", context.getBattleId());
    }

    public void logAction(String battleId, BattleAction action) {
        if (battleLogService != null) {
            battleLogService.logAction(battleId, action);
        }
    }

    public static class Builder {
        private BuffSystem buffSystem;
        private TimelineEngine timelineEngine;
        private DeckManager deckManager;
        private GameConfig gameConfig;
        private EffectProcessor effectProcessor;
        private BiConsumer<Enemy, BattleContext> intentGenerator;
        private BiFunction<Enemy, BattleContext, BattleAction> enemyActionExecutor;
        private BattleLogService battleLogService;
        private Runnable cleanupCallback;

        public Builder buffSystem(BuffSystem buffSystem) {
            this.buffSystem = buffSystem;
            return this;
        }

        public Builder timelineEngine(TimelineEngine timelineEngine) {
            this.timelineEngine = timelineEngine;
            return this;
        }

        public Builder deckManager(DeckManager deckManager) {
            this.deckManager = deckManager;
            return this;
        }

        public Builder gameConfig(GameConfig gameConfig) {
            this.gameConfig = gameConfig;
            return this;
        }

        public Builder effectProcessor(EffectProcessor effectProcessor) {
            this.effectProcessor = effectProcessor;
            return this;
        }

        public Builder intentGenerator(BiConsumer<Enemy, BattleContext> intentGenerator) {
            this.intentGenerator = intentGenerator;
            return this;
        }

        public Builder enemyActionExecutor(BiFunction<Enemy, BattleContext, BattleAction> enemyActionExecutor) {
            this.enemyActionExecutor = enemyActionExecutor;
            return this;
        }

        public Builder battleLogService(BattleLogService battleLogService) {
            this.battleLogService = battleLogService;
            return this;
        }

        public Builder cleanupCallback(Runnable cleanupCallback) {
            this.cleanupCallback = cleanupCallback;
            return this;
        }

        public BattlePipeline build() {
            PreTurnPhase preTurnPhase = new PreTurnPhase(
                    buffSystem, timelineEngine, deckManager, gameConfig, intentGenerator
            );

            ActionPhase actionPhase = new ActionPhase(
                    timelineEngine, deckManager, effectProcessor, enemyActionExecutor, battleLogService
            );

            PostActionPhase postActionPhase = new PostActionPhase(
                    buffSystem, timelineEngine, intentGenerator
            );

            DeathCheckPhase deathCheckPhase = new DeathCheckPhase(
                    battleLogService, cleanupCallback
            );

            preTurnPhase.setNext(actionPhase)
                    .setNext(postActionPhase)
                    .setNext(deathCheckPhase);

            return new BattlePipeline(preTurnPhase, battleLogService);
        }
    }
}
