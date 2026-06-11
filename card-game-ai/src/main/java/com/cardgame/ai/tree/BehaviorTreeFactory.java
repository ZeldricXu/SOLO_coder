package com.cardgame.ai.tree;

import com.cardgame.common.enums.BuffType;

public class BehaviorTreeFactory {

    public static BehaviorNode createAggressiveTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpAboveThreshold(0.3f))
                        .addChild(new ConditionNode.RandomChance(0.7))
                        .addChild(new ActionNode.AttackAction(12)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.PlayerCount(2))
                        .addChild(new ConditionNode.RandomChance(0.4))
                        .addChild(new ActionNode.AoEAttackAction(8)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.6))
                        .addChild(new ActionNode.MultiHitAttackAction(6, 2)))
                .addChild(new ActionNode.AttackAction(10));
    }

    public static BehaviorNode createDefensiveTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.5f))
                        .addChild(new ConditionNode.RandomChance(0.8))
                        .addChild(new ActionNode.DefendAction(15)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.3f))
                        .addChild(new ActionNode.DefendAction(20)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.4))
                        .addChild(new ActionNode.BuffSelfAction(BuffType.DEXTERITY, 2, 3)))
                .addChild(new ActionNode.AttackAction(8));
    }

    public static BehaviorNode createBalancedTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.4f))
                        .addChild(new ConditionNode.RandomChance(0.6))
                        .addChild(new ActionNode.DefendAction(12)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.DebuffTargetAction(BuffType.WEAK, 1, 2)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.BuffSelfAction(BuffType.STRENGTH, 1, 3)))
                .addChild(new ActionNode.AttackAction(10));
    }

    public static BehaviorNode createCasterTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.DebuffTargetAction(BuffType.VULNERABLE, 2, 2)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.25))
                        .addChild(new ActionNode.AoEAttackAction(10)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.BuffSelfAction(BuffType.STRENGTH, 2, 2)))
                .addChild(new ActionNode.AttackAction(10));
    }

    public static BehaviorNode createVampireBossTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.3f))
                        .addChild(new ConditionNode.RandomChance(0.9))
                        .addChild(new ActionNode.LifestealAttackAction(18, 0.5)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.6f))
                        .addChild(new ConditionNode.RandomChance(0.6))
                        .addChild(new ActionNode.LifestealAttackAction(15, 0.3)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.4))
                        .addChild(new ActionNode.DebuffTargetAction(BuffType.WEAK, 2, 3)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.BuffSelfAction(BuffType.STRENGTH, 2, 2)))
                .addChild(new ActionNode.MultiHitAttackAction(8, 3));
    }

    public static BehaviorNode createDragonBossTree() {
        return new SelectorNode()
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.25))
                        .addChild(new ActionNode.AoEAttackAction(20)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.RandomChance(0.3))
                        .addChild(new ActionNode.DebuffTargetAction(BuffType.BURN, 3, 3)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionPlayerCountCondition(2))
                        .addChild(new ConditionNode.RandomChance(0.4))
                        .addChild(new ActionNode.MultiHitAttackAction(12, 3)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.4f))
                        .addChild(new ConditionNode.RandomChance(0.7))
                        .addChild(new ActionNode.BuffSelfAction(BuffType.STRENGTH, 3, 3)))
                .addChild(new SequenceNode()
                        .addChild(new ConditionNode.HpBelowThreshold(0.3f))
                        .addChild(new ActionNode.DefendAction(25)))
                .addChild(new ActionNode.AttackAction(25));
    }

    private static class ConditionPlayerCountCondition extends BehaviorNode {
        private final int minCount;

        public ConditionPlayerCountCondition(int minCount) {
            this.minCount = minCount;
        }

        @Override
        public NodeStatus execute(Enemy enemy, com.cardgame.common.entity.BattleContext context) {
            return context.getAlivePlayers().size() >= minCount ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
        }
    }

    public static BehaviorNode getBehaviorTree(String treeId) {
        return switch (treeId) {
            case "aggressive" -> createAggressiveTree();
            case "defensive" -> createDefensiveTree();
            case "balanced" -> createBalancedTree();
            case "caster" -> createCasterTree();
            case "vampire_boss" -> createVampireBossTree();
            case "dragon_boss" -> createDragonBossTree();
            default -> createBalancedTree();
        };
    }
}
