package com.cardgame.map.generator;

import com.cardgame.common.enums.NodeType;
import com.cardgame.map.entity.GameMap;
import com.cardgame.map.entity.MapNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Map Generator Dead End Fix Tests")
class MapGeneratorDeadEndTest {

    private MapGenerator mapGenerator;

    @BeforeEach
    void setUp() {
        mapGenerator = new MapGenerator();
    }

    @Nested
    @DisplayName("Dead End Detection Tests")
    class DeadEndDetectionTests {

        @Test
        @DisplayName("Generated map should have no dead ends - all nodes reach boss")
        void generateMap_ShouldHaveNoDeadEnds() {
            int totalMaps = 10;
            int totalDeadEnds = 0;

            for (int i = 0; i < totalMaps; i++) {
                GameMap map = mapGenerator.generateMap("test-room-" + i, i, 20);
                int deadEnds = countDeadEnds(map);
                totalDeadEnds += deadEnds;

                assertThat(deadEnds)
                        .as("Map %d should have no dead ends, but found %d", i, deadEnds)
                        .isEqualTo(0);
            }

            System.out.printf("Tested %d maps, total dead ends found: %d%n", totalMaps, totalDeadEnds);
        }

        @Test
        @DisplayName("All nodes should be able to reach the boss node")
        void generateMap_AllNodesShouldReachBoss() {
            GameMap map = mapGenerator.generateMap("test-room", 12345, 30);

            for (List<MapNode> floorNodes : map.getNodes()) {
                for (MapNode node : floorNodes) {
                    boolean canReachBoss = canReachBoss(map, node, new HashSet<>());
                    assertThat(canReachBoss)
                            .as("Node %s at floor %d should reach boss", node.getNodeId(), node.getFloor())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("Dead ends should be fixed with portal nodes")
        void generateMap_DeadEnds_ShouldBeFixedWithPortals() {
            GameMap map = mapGenerator.generateMap("test-room", 999, 50);

            int portalCount = 0;
            for (List<MapNode> floorNodes : map.getNodes()) {
                for (MapNode node : floorNodes) {
                    if (node.getNodeType() == NodeType.PORTAL) {
                        portalCount++;
                        assertThat(node.getTargetNodeId()).isNotNull();
                        assertThat(node.getTargetFloor()).isGreaterThan(node.getFloor());
                        assertThat(node.getNextNodeIds()).hasSize(1);
                        assertThat(node.getNextNodeIds().get(0)).isEqualTo(node.getTargetNodeId());
                    }
                }
            }

            System.out.printf("Found %d portal nodes in map%n", portalCount);
        }
    }

    @Nested
    @DisplayName("Portal Node Tests")
    class PortalNodeTests {

        @Test
        @DisplayName("PORTAL node type should exist")
        void portalNodeType_ShouldExist() {
            NodeType portalType = NodeType.valueOf("PORTAL");
            assertThat(portalType).isNotNull();
        }

        @Test
        @DisplayName("Portal should target a valid node")
        void generateMap_Portals_ShouldTargetValidNodes() {
            GameMap map = mapGenerator.generateMap("test-room", 54321, 40);

            for (List<MapNode> floorNodes : map.getNodes()) {
                for (MapNode node : floorNodes) {
                    if (node.getNodeType() == NodeType.PORTAL) {
                        MapNode target = map.getNode(node.getTargetNodeId());
                        assertThat(target)
                                .as("Portal %s should target a valid node", node.getNodeId())
                                .isNotNull();
                        assertThat(target.getFloor())
                                .as("Portal target should be on a higher floor")
                                .isEqualTo(node.getTargetFloor());
                    }
                }
            }
        }

        @Test
        @DisplayName("Portal target should be reachable to boss")
        void generateMap_PortalTargets_ShouldReachBoss() {
            GameMap map = mapGenerator.generateMap("test-room", 11111, 30);

            for (List<MapNode> floorNodes : map.getNodes()) {
                for (MapNode node : floorNodes) {
                    if (node.getNodeType() == NodeType.PORTAL) {
                        MapNode target = map.getNode(node.getTargetNodeId());
                        boolean targetReachesBoss = canReachBoss(map, target, new HashSet<>());
                        assertThat(targetReachesBoss)
                                .as("Portal target %s should reach boss", node.getTargetNodeId())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("MapNode should have targetNodeId and targetFloor fields")
        void mapNode_ShouldHavePortalFields() {
            MapNode node = MapNode.builder()
                    .nodeType(NodeType.PORTAL)
                    .targetNodeId("target-node")
                    .targetFloor(10)
                    .build();

            assertThat(node.getTargetNodeId()).isEqualTo("target-node");
            assertThat(node.getTargetFloor()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Large Map Tests")
    class LargeMapTests {

        @Test
        @DisplayName("Large map (50 floors) should have no dead ends")
        void generateMap_LargeMap_ShouldHaveNoDeadEnds() {
            GameMap map = mapGenerator.generateMap("test-room", 99999, 50);

            int deadEnds = countDeadEnds(map);
            assertThat(deadEnds)
                    .as("50-floor map should have no dead ends, but found %d", deadEnds)
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("Very large map (100 floors) should have no dead ends")
        void generateMap_VeryLargeMap_ShouldHaveNoDeadEnds() {
            GameMap map = mapGenerator.generateMap("test-room", 123456, 100);

            int deadEnds = countDeadEnds(map);
            assertThat(deadEnds)
                    .as("100-floor map should have no dead ends, but found %d", deadEnds)
                    .isEqualTo(0);
        }
    }

    private int countDeadEnds(GameMap map) {
        int deadEnds = 0;
        int maxFloor = map.getMaxFloors();

        for (int floor = 0; floor < maxFloor - 1; floor++) {
            List<MapNode> floorNodes = map.getNodesAtFloor(floor);
            if (floorNodes == null) continue;

            for (MapNode node : floorNodes) {
                if (node.getNextNodeIds() == null || node.getNextNodeIds().isEmpty()) {
                    deadEnds++;
                    continue;
                }

                if (!canReachBoss(map, node, new HashSet<>())) {
                    deadEnds++;
                }
            }
        }

        return deadEnds;
    }

    private boolean canReachBoss(GameMap map, MapNode currentNode, Set<String> visited) {
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
}
