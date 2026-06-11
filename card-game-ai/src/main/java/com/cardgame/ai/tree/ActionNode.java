package com.cardgame.ai.tree;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import com.cardgame.common.utils.IdGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public abstract class ActionNode extends BehaviorNode {

    protected void dealDamage(Enemy attacker, Player target, int damage, BattleContext context, BattleAction action) {
        int actualDamage = context.calculateDamage(attacker, target, damage);
        int blockAbsorbed = Math.min(target.getBlock(), actualDamage);
        int hpDamage = actualDamage - blockAbsorbed;

        target.setBlock(Math.max(0, target.getBlock() - blockAbsorbed));
        target.setCurrentHp(Math.max(0, target.getCurrentHp() - hpDamage));

        action.setDamageDealt(actualDamage);
        action.setTargetId(target.getPlayerId());
    }

    protected void applyBuff(Enemy enemy, BuffType type, int stacks, int duration, BattleAction action) {
        Buff buff = Buff.builder()
                .type(type)
                .stacks(stacks)
                .duration(duration)
                .sourceId(enemy.getId())
                .isDebuff(false)
                .build();
        enemy.addBuff(buff);
        action.getBuffsApplied().put(type.name(), stacks);
    }

    protected void applyDebuff(Player target, BuffType type, int stacks, int duration, Enemy enemy, BattleAction action) {
        Buff buff = Buff.builder()
                .type(type)
                .stacks(stacks)
                .duration(duration)
                .sourceId(enemy.getId())
                .isDebuff(true)
                .build();
        target.addBuff(buff);
        action.getBuffsApplied().put(type.name(), stacks);
    }

    protected void addBlock(Enemy enemy, int block, BattleContext context, BattleAction action) {
        int actualBlock = context.calculateBlock(enemy, block);
        enemy.addBlock(actualBlock);
        action.setBlockGained(actualBlock);
    }

    protected BattleAction createAction(Enemy enemy, String actionType) {
        return BattleAction.builder()
                .actionId(IdGenerator.generateUUID())
                .actorId(enemy.getId())
                .isPlayerAction(false)
                .actionType(actionType)
                .timestamp(System.currentTimeMillis())
                .targetIds(new ArrayList<>())
                .buffsApplied(new HashMap<>())
                .buffsRemoved(new HashMap<>())
                .build();
    }

    public static class AttackAction extends ActionNode {
        private final int baseDamage;

        public AttackAction(int baseDamage) {
            this.baseDamage = baseDamage;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            Player target = selectLowestHpTarget(context);
            if (target == null) return NodeStatus.FAILURE;

            BattleAction action = createAction(enemy, "ATTACK");
            int damage = baseDamage + enemy.getDifficultyModifier();
            dealDamage(enemy, target, damage, context, action);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("ATTACK")
                    .value(damage)
                    .targetId(target.getPlayerId())
                    .description("攻击 " + target.getName())
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class AoEAttackAction extends ActionNode {
        private final int baseDamage;

        public AoEAttackAction(int baseDamage) {
            this.baseDamage = baseDamage;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            List<Player> targets = context.getAlivePlayers();
            if (targets.isEmpty()) return NodeStatus.FAILURE;

            BattleAction action = createAction(enemy, "AOE_ATTACK");
            int damage = baseDamage + enemy.getDifficultyModifier();

            for (Player target : targets) {
                dealDamage(enemy, target, damage, context, action);
                action.getTargetIds().add(target.getPlayerId());
            }

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("AOE_ATTACK")
                    .value(damage)
                    .description("群体攻击")
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class DefendAction extends ActionNode {
        private final int baseBlock;

        public DefendAction(int baseBlock) {
            this.baseBlock = baseBlock;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            BattleAction action = createAction(enemy, "DEFEND");
            int block = baseBlock + enemy.getDifficultyModifier() / 2;
            addBlock(enemy, block, context, action);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("DEFEND")
                    .value(block)
                    .description("防御 " + block)
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class BuffSelfAction extends ActionNode {
        private final BuffType buffType;
        private final int stacks;
        private final int duration;

        public BuffSelfAction(BuffType buffType, int stacks, int duration) {
            this.buffType = buffType;
            this.stacks = stacks;
            this.duration = duration;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            BattleAction action = createAction(enemy, "BUFF_SELF");
            applyBuff(enemy, buffType, stacks, duration, action);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("BUFF")
                    .value(stacks)
                    .description("获得 " + buffType.name() + " x" + stacks)
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class DebuffTargetAction extends ActionNode {
        private final BuffType debuffType;
        private final int stacks;
        private final int duration;

        public DebuffTargetAction(BuffType debuffType, int stacks, int duration) {
            this.debuffType = debuffType;
            this.stacks = stacks;
            this.duration = duration;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            Player target = selectLowestHpTarget(context);
            if (target == null) return NodeStatus.FAILURE;

            BattleAction action = createAction(enemy, "DEBUFF_TARGET");
            applyDebuff(target, debuffType, stacks, duration, enemy, action);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("DEBUFF")
                    .value(stacks)
                    .targetId(target.getPlayerId())
                    .description("施加 " + debuffType.name() + " x" + stacks)
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class HealSelfAction extends ActionNode {
        private final int amount;

        public HealSelfAction(int amount) {
            this.amount = amount;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            BattleAction action = createAction(enemy, "HEAL_SELF");
            int healAmount = amount + enemy.getDifficultyModifier();
            enemy.setCurrentHp(Math.min(enemy.getMaxHp(), enemy.getCurrentHp() + healAmount));
            action.setHealDone(healAmount);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("HEAL")
                    .value(healAmount)
                    .description("恢复 " + healAmount + " 生命")
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class LifestealAttackAction extends ActionNode {
        private final int baseDamage;
        private final double lifestealPercent;

        public LifestealAttackAction(int baseDamage, double lifestealPercent) {
            this.baseDamage = baseDamage;
            this.lifestealPercent = lifestealPercent;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            Player target = selectLowestHpTarget(context);
            if (target == null) return NodeStatus.FAILURE;

            BattleAction action = createAction(enemy, "LIFESTEAL_ATTACK");
            int damage = baseDamage + enemy.getDifficultyModifier();
            dealDamage(enemy, target, damage, context, action);

            int healAmount = (int) (damage * lifestealPercent);
            enemy.setCurrentHp(Math.min(enemy.getMaxHp(), enemy.getCurrentHp() + healAmount));
            action.setHealDone(healAmount);

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("LIFESTEAL")
                    .value(damage)
                    .targetId(target.getPlayerId())
                    .description("吸血攻击 " + damage + "，恢复 " + healAmount)
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }

    public static class MultiHitAttackAction extends ActionNode {
        private final int baseDamage;
        private final int hits;

        public MultiHitAttackAction(int baseDamage, int hits) {
            this.baseDamage = baseDamage;
            this.hits = hits;
        }

        @Override
        public NodeStatus execute(Enemy enemy, BattleContext context) {
            Player target = selectLowestHpTarget(context);
            if (target == null) return NodeStatus.FAILURE;

            BattleAction action = createAction(enemy, "MULTI_HIT_ATTACK");
            int damage = baseDamage + enemy.getDifficultyModifier() / hits;
            int totalDamage = 0;

            for (int i = 0; i < hits && target.isAlive(); i++) {
                int hitDamage = context.calculateDamage(enemy, target, damage);
                int blockAbsorbed = Math.min(target.getBlock(), hitDamage);
                int hpDamage = hitDamage - blockAbsorbed;
                target.setBlock(Math.max(0, target.getBlock() - blockAbsorbed));
                target.setCurrentHp(Math.max(0, target.getCurrentHp() - hpDamage));
                totalDamage += hitDamage;
            }

            action.setDamageDealt(totalDamage);
            action.setTargetId(target.getPlayerId());

            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("MULTI_HIT")
                    .value(damage * hits)
                    .targetId(target.getPlayerId())
                    .description("连击 " + hits + "x" + damage)
                    .build());

            context.addAction(action);
            return NodeStatus.SUCCESS;
        }
    }
}
