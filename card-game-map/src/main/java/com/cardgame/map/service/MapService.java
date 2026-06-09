package com.cardgame.map.service;

import com.cardgame.map.entity.GameMap;
import com.cardgame.map.entity.MapNode;
import com.cardgame.map.generator.MapGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MapService {

    private final Map<String, GameMap> activeMaps = new ConcurrentHashMap<>();

    @Autowired
    private MapGenerator mapGenerator;

    public GameMap createMap(String roomId) {
        return createMap(roomId, System.currentTimeMillis());
    }

    public GameMap createMap(String roomId, long seed) {
        GameMap map = mapGenerator.generateMap(roomId, seed, 50);
        activeMaps.put(roomId, map);
        log.info("Created map {} for room {}", map.getMapId(), roomId);
        return map;
    }

    public GameMap getMap(String roomId) {
        return activeMaps.get(roomId);
    }

    public GameMap selectNode(String roomId, String nodeId) {
        GameMap map = activeMaps.get(roomId);
        if (map == null) {
            return null;
        }

        MapNode node = map.getNode(nodeId);
        if (node == null) {
            return null;
        }

        List<MapNode> currentNodes = map.getCurrentNodes();
        boolean canMove = false;
        for (MapNode current : currentNodes) {
            if (current.getNextNodeIds().contains(nodeId) && node.isAccessible()) {
                canMove = true;
                break;
            }
        }

        if (!canMove) {
            log.warn("Cannot move to node {} from current position in room {}", nodeId, roomId);
            return null;
        }

        map.moveToNode(nodeId);
        log.info("Moved to node {} ({}) in room {}", nodeId, node.getNodeType(), roomId);

        return map;
    }

    public List<MapNode> getAccessibleNodes(String roomId) {
        GameMap map = activeMaps.get(roomId);
        if (map == null) {
            return null;
        }
        return map.getAccessibleNodes();
    }

    public void removeMap(String roomId) {
        activeMaps.remove(roomId);
        log.info("Removed map for room {}", roomId);
    }

    public int getActiveMapCount() {
        return activeMaps.size();
    }

    public boolean isMapComplete(String roomId) {
        GameMap map = activeMaps.get(roomId);
        return map != null && map.isComplete();
    }
}
