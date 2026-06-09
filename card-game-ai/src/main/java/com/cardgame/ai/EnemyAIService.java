package com.cardgame.ai;

import com.cardgame.ai.tree.BehaviorNode;
import com.cardgame.ai.tree.BehaviorTreeFactory;
import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BuffType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class EnemyAIService {

    private final Map<String, BehaviorNode> behaviorTreeCache = new HashMap<>();

    @Autowired
    private EnemyTemplateLibrary enemyTemplateLibrary;

    public void generateIntent(Enemy enemy, BattleContext context) {
        BehaviorNode tree = getBehaviorTree(enemy);
        if (tree == null) {
            generateDefaultIntent(enemy, context);
            return;
        }

        BehaviorNode.NodeStatus status = tree.execute(enemy, context);
        if (status != BehaviorNode.NodeStatus.SUCCESS) {
            generateDefaultIntent(enemy, context);
        }

        log.debug("Generated intent for enemy {}: {}", enemy.getName(), 
                  enemy.getIntents().isEmpty() ? "none" : enemy.getIntents().get(0).getDescription());
    }

    public BattleAction executeEnemyAction(Enemy enemy, BattleContext context) {
        if (enemy.getIntents().isEmpty()) {
            generateIntent(enemy, context);
        }

        BehaviorNode tree = getBehaviorTree(enemy);
        if (tree == null) {
            return executeDefaultAction(enemy, context);
        }

        int actionCount = context.getActionHistory().size();
        BehaviorNode.NodeStatus status = tree.execute(enemy, context);
        
        if (status == BehaviorNode.NodeStatus.SUCCESS && context.getActionHistory().size() > actionCount) {
            return context.getActionHistory().get(context.getActionHistory().size() - 1);
        }

        return executeDefaultAction(enemy, context);
    }

    private BehaviorNode getBehaviorTree(Enemy enemy) {
        String treeId = enemy.getAiBehaviorTreeId();
        if (treeId == null) {
            treeId = "balanced";
        }

        return behaviorTreeCache.computeIfAbsent(treeId, BehaviorTreeFactory::getBehaviorTree);
    }

    private void generateDefaultIntent(Enemy enemy, BattleContext context) {
        Player target = selectTarget(context);
        if (target == null) return;

        int damage = enemy.getBaseDamage() + enemy.getDifficultyModifier();

        enemy.clearIntents();
        enemy.addIntent(Enemy.Intent.builder()
                .type("ATTACK")
                .value(damage)
                .targetId(target.getPlayerId())
                .description("攻击 " + target.getName())
                .build());
    }

    private BattleAction executeDefaultAction(Enemy enemy, BattleContext context) {
        Player target = selectTarget(context);
        if (target == null) return null;

        BattleAction action = BattleAction.builder()
                .actionId(com.cardgame.common.utils.IdGenerator.generateUUID())
                .actorId(enemy.getId())
                .isPlayerAction(false)
                .actionType("ATTACK")
                .timestamp(System.currentTimeMillis())
                .buffsApplied(new HashMap<>())
                .buffsRemoved(new HashMap<>())
                .targetId(target.getPlayerId())
                .build();

        int damage = enemy.getBaseDamage() + enemy.getDifficultyModifier();
        int actualDamage = context.calculateDamage(enemy, target, damage);
        int blockAbsorbed = Math.min(target.getBlock(), actualDamage);
        int hpDamage = actualDamage - blockAbsorbed;

        target.setBlock(Math.max(0, target.getBlock() - blockAbsorbed));
        target.setCurrentHp(Math.max(0, target.getCurrentHp() - hpDamage));
        action.setDamageDealt(actualDamage);

        context.addAction(action);
        return action;
    }

    private Player selectTarget(BattleContext context) {
        var alivePlayers = context.getAlivePlayers();
        if (alivePlayers.isEmpty()) return null;

        Player lowestHpPlayer = alivePlayers.get(0);
        for (Player p : alivePlayers) {
            if (p.getCurrentHp() < lowestHpPlayer.getCurrentHp()) {
                lowestHpPlayer = p;
            }
        }
        return lowestHpPlayer;
    }

    public void applyDifficultyScaling(Enemy enemy, int floor) {
        float scaling = enemy.getDifficultyScaling();
        int floorBonus = (int) Math.pow(scaling, floor / 5.0);
        enemy.setDifficultyModifier(floorBonus);

        int scaledMaxHp = enemy.getMaxHp() + floorBonus * 5;
        enemy.setMaxHp(scaledMaxHp);
        enemy.setCurrentHp(scaledMaxHp);
    }
}
