package com.cardgame.battle.pipeline;

import com.cardgame.battle.entity.BattleContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BattlePhase {

    protected BattlePhase next;

    public BattlePhase setNext(BattlePhase next) {
        this.next = next;
        return next;
    }

    public abstract void execute(BattleContext context) throws Exception;

    protected void fireNext(BattleContext context) throws Exception {
        if (next != null) {
            log.debug("Executing next phase: {}", next.getClass().getSimpleName());
            next.execute(context);
        }
    }

    protected boolean shouldContinue(BattleContext context) {
        return !context.isBattleOver();
    }
}
