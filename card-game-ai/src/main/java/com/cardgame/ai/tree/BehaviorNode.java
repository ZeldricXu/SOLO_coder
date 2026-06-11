package com.cardgame.ai.tree;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;

public abstract class BehaviorNode {

    public enum NodeStatus {
        SUCCESS,
        FAILURE,
        RUNNING
    }

    protected BehaviorNode parent;

    public abstract NodeStatus execute(Enemy enemy, BattleContext context);

    public BehaviorNode getParent() {
        return parent;
    }

    public void setParent(BehaviorNode parent) {
        this.parent = parent;
    }

    protected Player selectRandomTarget(BattleContext context) {
        var alivePlayers = context.getAlivePlayers();
        if (alivePlayers.isEmpty()) return null;
        int index = (int) (Math.random() * alivePlayers.size());
        return alivePlayers.get(index);
    }

    protected Player selectLowestHpTarget(BattleContext context) {
        var alivePlayers = context.getAlivePlayers();
        if (alivePlayers.isEmpty()) return null;

        Player lowest = alivePlayers.get(0);
        for (Player p : alivePlayers) {
            if (p.getCurrentHp() < lowest.getCurrentHp()) {
                lowest = p;
            }
        }
        return lowest;
    }
}
