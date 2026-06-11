package com.cardgame.map.entity;

import com.cardgame.common.enums.NodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapNode {
    private String nodeId;
    private NodeType nodeType;
    private int floor;
    private int column;
    private int x;
    private int y;
    @Builder.Default
    private List<String> nextNodeIds = new ArrayList<>();
    @Builder.Default
    private List<String> prevNodeIds = new ArrayList<>();
    private boolean visited;
    private boolean current;
    private boolean accessible;
    private String eventId;
    private String enemyGroupId;
    private String shopId;
    private int goldReward;
    private int cardRewardCount;
    private String targetNodeId;
    private int targetFloor;

    public boolean canMoveTo(MapNode nextNode) {
        return nextNodeIds.contains(nextNode.getNodeId()) && nextNode.isAccessible();
    }

    public void visit() {
        this.visited = true;
        this.current = false;
        for (String nextId : nextNodeIds) {
        }
    }
}
