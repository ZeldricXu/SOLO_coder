package com.cardgame.ai.tree;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.Enemy;

import java.util.ArrayList;
import java.util.List;

public class SequenceNode extends BehaviorNode {

    private final List<BehaviorNode> children;

    public SequenceNode() {
        this.children = new ArrayList<>();
    }

    public SequenceNode addChild(BehaviorNode child) {
        child.setParent(this);
        children.add(child);
        return this;
    }

    @Override
    public NodeStatus execute(Enemy enemy, BattleContext context) {
        for (BehaviorNode child : children) {
            NodeStatus status = child.execute(enemy, context);
            if (status != NodeStatus.SUCCESS) {
                return status;
            }
        }
        return NodeStatus.SUCCESS;
    }

    public List<BehaviorNode> getChildren() {
        return children;
    }
}
