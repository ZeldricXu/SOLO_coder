package com.cardgame.ai.tree;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BuffType;

public abstract class ConditionNode extends BehaviorNode {

    protected abstract boolean checkCondition(Enemy enemy, BattleContext context);

    @Override
    public NodeStatus execute(Enemy enemy, BattleContext context) {
        return checkCondition(enemy, context) ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }

    public static class HpBelowThreshold extends ConditionNode {
        private final float threshold;

        public HpBelowThreshold(float threshold) {
            this.threshold = threshold;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            float hpPercent = (float) enemy.getCurrentHp() / enemy.getMaxHp();
            return hpPercent < threshold;
        }
    }

    public static class HpAboveThreshold extends ConditionNode {
        private final float threshold;

        public HpAboveThreshold(float threshold) {
            this.threshold = threshold;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            float hpPercent = (float) enemy.getCurrentHp() / enemy.getMaxHp();
            return hpPercent > threshold;
        }
    }

    public static class TargetHasBuff extends ConditionNode {
        private final BuffType buffType;

        public TargetHasBuff(BuffType buffType) {
            this.buffType = buffType;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            for (Player player : context.getAlivePlayers()) {
                if (player.hasBuff(buffType.name())) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class RandomChance extends ConditionNode {
        private final double chance;

        public RandomChance(double chance) {
            this.chance = chance;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            return Math.random() < chance;
        }
    }

    public static class HasBuff extends ConditionNode {
        private final BuffType buffType;

        public HasBuff(BuffType buffType) {
            this.buffType = buffType;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            return enemy.hasBuff(buffType.name());
        }
    }

    public static class PlayerCount extends ConditionNode {
        private final int minCount;

        public PlayerCount(int minCount) {
            this.minCount = minCount;
        }

        @Override
        protected boolean checkCondition(Enemy enemy, BattleContext context) {
            return context.getAlivePlayers().size() >= minCount;
        }
    }
}
