package com.cardgame.map.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMap {
    private String mapId;
    private String roomId;
    private long seed;
    private int currentFloor;
    private int maxFloors;
    @Builder.Default
    private List<List<MapNode>> nodes = new ArrayList<>();
    @Builder.Default
    private Map<String, MapNode> nodeMap = new HashMap<>();
    @Builder.Default
    private List<String> currentNodeIds = new ArrayList<>();
    private MapNode bossNode;
    private MapNode startNode;
    private long createdAt;
    private boolean completed;

    public MapNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    public List<MapNode> getCurrentNodes() {
        List<MapNode> result = new ArrayList<>();
        for (String nodeId : currentNodeIds) {
            MapNode node = nodeMap.get(nodeId);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    public List<MapNode> getAccessibleNodes() {
        List<MapNode> result = new ArrayList<>();
        for (MapNode node : nodeMap.values()) {
            if (node.isAccessible() && !node.isVisited()) {
                result.add(node);
            }
        }
        return result;
    }

    public List<MapNode> getNodesAtFloor(int floor) {
        if (floor < 0 || floor >= nodes.size()) {
            return new ArrayList<>();
        }
        return nodes.get(floor);
    }

    public void moveToNode(String nodeId) {
        MapNode node = nodeMap.get(nodeId);
        if (node == null) return;

        for (String currentId : currentNodeIds) {
            MapNode currentNode = nodeMap.get(currentId);
            if (currentNode != null) {
                currentNode.setCurrent(false);
                currentNode.setVisited(true);
            }
        }

        currentNodeIds.clear();
        currentNodeIds.add(nodeId);
        node.setCurrent(true);
        currentFloor = node.getFloor();

        updateAccessibleNodes(node);
    }

    private void updateAccessibleNodes(MapNode currentNode) {
        for (MapNode node : nodeMap.values()) {
            node.setAccessible(false);
        }

        for (String nextId : currentNode.getNextNodeIds()) {
            MapNode nextNode = nodeMap.get(nextId);
            if (nextNode != null) {
                nextNode.setAccessible(true);
            }
        }
    }

    public boolean isComplete() {
        return bossNode != null && bossNode.isVisited();
    }
}
