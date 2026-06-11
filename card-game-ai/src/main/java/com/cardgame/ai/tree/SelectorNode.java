package com.cardgame.ai.tree;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;

import java.util.ArrayList;
import java.util.List;

public class SelectorNode extends BehaviorNode {

    private final List<BehaviorNode> children;

    public SelectorNode() {
        this.children = new ArrayList<>();
    }

    public SelectorNode addChild(BehaviorNode child) {
        child.setParent(this);
        children.add(child);
        return this;
    }

    @Override
    public NodeStatus execute(Enemy enemy, BattleContext context) {
        for (BehaviorNode child : children) {
            NodeStatus status = child.execute(enemy, context);
            if (status != NodeStatus.FAILURE) {
                return status;
            }
        }
        return NodeStatus.FAILURE;
    }

    public List<BehaviorNode> getChildren() {
        return children;
    }
}
