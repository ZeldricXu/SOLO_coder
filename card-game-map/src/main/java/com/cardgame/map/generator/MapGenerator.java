package com.cardgame.map.generator;

import com.cardgame.common.enums.NodeType;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.common.utils.SeededRandom;
import com.cardgame.map.entity.GameMap;
import com.cardgame.map.entity.MapNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MapGenerator {

    private static final int MIN_NODES_PER_FLOOR = 2;
    private static final int MAX_NODES_PER_FLOOR = 4;
    private static final int DEFAULT_MAX_FLOORS = 50;
    private static final int BOSS_FLOOR_INTERVAL = 10;

    public GameMap generateMap(String roomId) {
        return generateMap(roomId, System.currentTimeMillis(), DEFAULT_MAX_FLOORS);
    }

    public GameMap generateMap(String roomId, long seed, int maxFloors) {
        SeededRandom random = new SeededRandom(seed);
        GameMap map = GameMap.builder()
                .mapId(IdGenerator.generateUUID())
                .roomId(roomId)
                .seed(seed)
                .currentFloor(0)
                .maxFloors(maxFloors)
                .nodes(new ArrayList<>())
                .nodeMap(new java.util.HashMap<>())
                .currentNodeIds(new ArrayList<>())
                .createdAt(System.currentTimeMillis())
                .build();

        generateNodes(map, random);
        connectNodes(map, random);
        fixDeadEnds(map, random);
        setupStartAndBoss(map);

        log.info("Generated map {} for room {} with seed {}, {} floors", 
                map.getMapId(), roomId, seed, map.getNodes().size());

        return map;
    }

    private void generateNodes(GameMap map, SeededRandom random) {
        for (int floor = 0; floor < map.getMaxFloors(); floor++) {
            List<MapNode> floorNodes = new ArrayList<>();

            if (floor == 0) {
                MapNode startNode = createNode(NodeType.START, floor, 0, 0);
                floorNodes.add(startNode);
                map.setStartNode(startNode);
            } else if (isBossFloor(floor, map.getMaxFloors())) {
                MapNode bossNode = createNode(NodeType.BOSS, floor, 0, 0);
                floorNodes.add(bossNode);
                map.setBossNode(bossNode);
            } else if (isEliteFloor(floor)) {
                int nodeCount = random.nextInt(MIN_NODES_PER_FLOOR, MAX_NODES_PER_FLOOR - 1);
                for (int col = 0; col < nodeCount; col++) {
                    NodeType type = col == 0 ? NodeType.ELITE : getRandomNodeType(random, floor);
                    floorNodes.add(createNode(type, floor, col, nodeCount));
                }
            } else if (isRestFloor(floor)) {
                floorNodes.add(createNode(NodeType.REST, floor, 0, 1));
                if (random.nextBoolean()) {
                    floorNodes.add(createNode(NodeType.SHOP, floor, 1, 2));
                }
            } else {
                int nodeCount = random.nextInt(MIN_NODES_PER_FLOOR, MAX_NODES_PER_FLOOR);
                for (int col = 0; col < nodeCount; col++) {
                    NodeType type = getRandomNodeType(random, floor);
                    floorNodes.add(createNode(type, floor, col, nodeCount));
                }
            }

            for (MapNode node : floorNodes) {
                map.getNodeMap().put(node.getNodeId(), node);
            }
            map.getNodes().add(floorNodes);
        }
    }

    private void connectNodes(GameMap map, SeededRandom random) {
        for (int floor = 0; floor < map.getMaxFloors() - 1; floor++) {
            List<MapNode> currentFloor = map.getNodesAtFloor(floor);
            List<MapNode> nextFloor = map.getNodesAtFloor(floor + 1);

            for (MapNode current : currentFloor) {
                int connections = Math.min(random.nextInt(1, 3), nextFloor.size());
                List<Integer> targetIndices = new ArrayList<>();

                for (int i = 0; i < nextFloor.size(); i++) {
                    targetIndices.add(i);
                }
                random.shuffle(targetIndices);

                for (int i = 0; i < connections; i++) {
                    int targetIdx = targetIndices.get(i);
                    MapNode next = nextFloor.get(targetIdx);
                    current.getNextNodeIds().add(next.getNodeId());
                    next.getPrevNodeIds().add(current.getNodeId());
                }
            }

            for (MapNode next : nextFloor) {
                if (next.getPrevNodeIds().isEmpty()) {
                    MapNode current = currentFloor.get(random.nextInt(currentFloor.size()));
                    current.getNextNodeIds().add(next.getNodeId());
                    next.getPrevNodeIds().add(current.getNodeId());
                }
            }
        }
    }

    private void setupStartAndBoss(GameMap map) {
        MapNode startNode = map.getStartNode();
        if (startNode != null) {
            startNode.setCurrent(true);
            startNode.setAccessible(true);
            map.getCurrentNodeIds().add(startNode.getNodeId());

            for (String nextId : startNode.getNextNodeIds()) {
                MapNode nextNode = map.getNode(nextId);
                if (nextNode != null) {
                    nextNode.setAccessible(true);
                }
            }
        }
    }

    private void fixDeadEnds(GameMap map, SeededRandom random) {
        int deadEndCount = 0;
        int portalCount = 0;

        for (int floor = 0; floor < map.getMaxFloors() - 1; floor++) {
            List<MapNode> floorNodes = map.getNodesAtFloor(floor);

            for (MapNode node : floorNodes) {
                if (node.getNextNodeIds().isEmpty()) {
                    deadEndCount++;
                    MapNode portalNode = addPortalNode(map, node, random);
                    portalCount++;
                    log.debug("Fixed dead end at floor {} col {}: added portal to {}", 
                            floor, node.getColumn(), portalNode.getFloor());
                } else if (!canReachBoss(map, node, new java.util.HashSet<>())) {
                    deadEndCount++;
                    MapNode portalNode = addPortalNode(map, node, random);
                    portalCount++;
                    log.debug("Fixed unreachable path at floor {} col {}: added portal to {}", 
                            floor, node.getColumn(), portalNode.getFloor());
                }
            }
        }

        if (deadEndCount > 0) {
            log.info("Fixed {} dead ends by adding {} portal nodes", deadEndCount, portalCount);
        }
    }

    private MapNode addPortalNode(GameMap map, MapNode deadEndNode, SeededRandom random) {
        int targetFloor = findNextBossFloor(deadEndNode.getFloor(), map.getMaxFloors());
        if (targetFloor <= deadEndNode.getFloor()) {
            targetFloor = Math.min(deadEndNode.getFloor() + 3, map.getMaxFloors() - 1);
        }

        List<MapNode> targetFloorNodes = map.getNodesAtFloor(targetFloor);
        if (targetFloorNodes == null || targetFloorNodes.isEmpty()) {
            if (targetFloor < map.getMaxFloors() - 1) {
                targetFloor = findNextBossFloor(targetFloor + 1, map.getMaxFloors());
                targetFloorNodes = map.getNodesAtFloor(targetFloor);
            }
            if (targetFloorNodes == null || targetFloorNodes.isEmpty()) {
                targetFloor = map.getMaxFloors() - 1;
                targetFloorNodes = map.getNodesAtFloor(targetFloor);
            }
        }

        MapNode targetNode = targetFloorNodes.get(random.nextInt(targetFloorNodes.size()));

        MapNode portalNode = MapNode.builder()
                .nodeId(IdGenerator.generateUUID())
                .nodeType(NodeType.PORTAL)
                .floor(deadEndNode.getFloor())
                .column(deadEndNode.getColumn() + 1000)
                .x(deadEndNode.getX() + 50)
                .y(deadEndNode.getY())
                .nextNodeIds(new ArrayList<>(java.util.Collections.singletonList(targetNode.getNodeId())))
                .prevNodeIds(new ArrayList<>(java.util.Collections.singletonList(deadEndNode.getNodeId())))
                .visited(false)
                .current(false)
                .accessible(false)
                .goldReward(0)
                .cardRewardCount(0)
                .targetNodeId(targetNode.getNodeId())
                .targetFloor(targetFloor)
                .build();

        deadEndNode.getNextNodeIds().add(portalNode.getNodeId());
        targetNode.getPrevNodeIds().add(portalNode.getNodeId());

        map.getNodeMap().put(portalNode.getNodeId(), portalNode);

        List<MapNode> currentFloorNodes = map.getNodesAtFloor(deadEndNode.getFloor());
        if (currentFloorNodes != null) {
            currentFloorNodes.add(portalNode);
        }

        return portalNode;
    }

    private boolean canReachBoss(GameMap map, MapNode currentNode, java.util.Set<String> visited) {
        if (visited.contains(currentNode.getNodeId())) {
            return false;
        }
        visited.add(currentNode.getNodeId());

        if (currentNode.getNodeType() == NodeType.BOSS) {
            return true;
        }

        if (currentNode.getNextNodeIds() == null || currentNode.getNextNodeIds().isEmpty()) {
            return false;
        }

        for (String nextId : currentNode.getNextNodeIds()) {
            MapNode nextNode = map.getNode(nextId);
            if (nextNode != null && canReachBoss(map, nextNode, visited)) {
                return true;
            }
        }

        return false;
    }

    private int findNextBossFloor(int currentFloor, int maxFloors) {
        for (int floor = currentFloor + 1; floor < maxFloors; floor++) {
            if (isBossFloor(floor, maxFloors)) {
                return floor;
            }
        }
        return maxFloors - 1;
    }

    private MapNode createNode(NodeType type, int floor, int col, int totalCols) {
        int x = totalCols > 1 ? (col * 200) / (totalCols - 1) - 100 : 0;
        int y = floor * 100;

        return MapNode.builder()
                .nodeId(IdGenerator.generateUUID())
                .nodeType(type)
                .floor(floor)
                .column(col)
                .x(x)
                .y(y)
                .nextNodeIds(new ArrayList<>())
                .prevNodeIds(new ArrayList<>())
                .visited(false)
                .current(false)
                .accessible(false)
                .goldReward(type == NodeType.CHEST || type == NodeType.TREASURE ? 50 + floor * 10 : 0)
                .cardRewardCount(type == NodeType.BATTLE || type == NodeType.ELITE || type == NodeType.BOSS ? 3 : 0)
                .build();
    }

    private NodeType getRandomNodeType(SeededRandom random, int floor) {
        double roll = random.nextDouble();
        double battleWeight = 0.55;
        double eventWeight = 0.15;
        double shopWeight = 0.08;
        double restWeight = 0.07;
        double chestWeight = 0.08;
        double eliteWeight = 0.07;

        if (floor > 20) {
            battleWeight = 0.5;
            eliteWeight = 0.12;
        }

        double cumulative = 0;

        cumulative += battleWeight;
        if (roll < cumulative) return NodeType.BATTLE;

        cumulative += eliteWeight;
        if (roll < cumulative) return NodeType.ELITE;

        cumulative += eventWeight;
        if (roll < cumulative) return NodeType.EVENT;

        cumulative += shopWeight;
        if (roll < cumulative) return NodeType.SHOP;

        cumulative += restWeight;
        if (roll < cumulative) return NodeType.REST;

        cumulative += chestWeight;
        if (roll < cumulative) return NodeType.CHEST;

        return NodeType.BATTLE;
    }

    private boolean isBossFloor(int floor, int maxFloors) {
        return floor > 0 && (floor % BOSS_FLOOR_INTERVAL == 0 || floor == maxFloors - 1);
    }

    private boolean isEliteFloor(int floor) {
        return floor % 5 == 0 && floor % 10 != 0;
    }

    private boolean isRestFloor(int floor) {
        return floor % 3 == 0 && floor % 5 != 0 && floor % 10 != 0;
    }

    public GameMap regenerateMap(GameMap existingMap) {
        return generateMap(existingMap.getRoomId(), existingMap.getSeed(), existingMap.getMaxFloors());
    }
}
