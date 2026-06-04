package com.battle.platform.battlefield;

import com.battle.platform.config.BattlefieldProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AOIGrid {

    private final BattlefieldProperties properties;

    private final ConcurrentHashMap<String, Set<Long>> grids = new ConcurrentHashMap<>();

    private String gridKey(int gridX, int gridZ) {
        return gridX + ":" + gridZ;
    }

    public void addPlayer(Long playerId, double x, double z) {
        int gx = (int) (x / properties.getAoiGridSize());
        int gz = (int) (z / properties.getAoiGridSize());
        String key = gridKey(gx, gz);
        grids.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    public void removePlayer(Long playerId, double x, double z) {
        int gx = (int) (x / properties.getAoiGridSize());
        int gz = (int) (z / properties.getAoiGridSize());
        String key = gridKey(gx, gz);
        Set<Long> set = grids.get(key);
        if (set != null) {
            set.remove(playerId);
            if (set.isEmpty()) {
                grids.remove(key);
            }
        }
    }

    public void updatePlayer(Long playerId, double oldX, double oldZ, double newX, double newZ) {
        int oldGx = (int) (oldX / properties.getAoiGridSize());
        int oldGz = (int) (oldZ / properties.getAoiGridSize());
        int newGx = (int) (newX / properties.getAoiGridSize());
        int newGz = (int) (newZ / properties.getAoiGridSize());

        if (oldGx != newGx || oldGz != newGz) {
            removePlayer(playerId, oldX, oldZ);
            addPlayer(playerId, newX, newZ);
        }
    }

    public Set<Long> getNearbyPlayers(double x, double z) {
        Set<Long> nearby = new HashSet<>();
        int gx = (int) (x / properties.getAoiGridSize());
        int gz = (int) (z / properties.getAoiGridSize());

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                String key = gridKey(gx + dx, gz + dz);
                Set<Long> players = grids.get(key);
                if (players != null) {
                    nearby.addAll(players);
                }
            }
        }

        return nearby;
    }

    public void clear() {
        grids.clear();
    }
}
