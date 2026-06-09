package com.cardgame.room.manager;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.room.entity.MatchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MatchmakingManager {

    private final Queue<MatchRequest> matchQueue = new LinkedList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private GameConfig gameConfig;

    public MatchmakingManager() {
        scheduler.scheduleAtFixedRate(this::processMatchmaking, 1, 1, TimeUnit.SECONDS);
    }

    public void addToMatchQueue(String playerId, String playerName, PlayerClass playerClass, int playerLevel) {
        if (matchQueue.size() >= gameConfig.getMaxMatchQueueSize()) {
            log.warn("Match queue is full, rejecting player: {}", playerId);
            return;
        }

        MatchRequest request = MatchRequest.builder()
                .playerId(playerId)
                .playerName(playerName)
                .playerClass(playerClass)
                .playerLevel(playerLevel)
                .requestTime(System.currentTimeMillis())
                .targetPlayers(4)
                .build();

        matchQueue.add(request);
        log.info("Added player {} to match queue. Queue size: {}", playerId, matchQueue.size());
    }

    public void removeFromMatchQueue(String playerId) {
        matchQueue.removeIf(request -> request.getPlayerId().equals(playerId));
    }

    public int getQueuePosition(String playerId) {
        int position = 1;
        for (MatchRequest request : matchQueue) {
            if (request.getPlayerId().equals(playerId)) {
                return position;
            }
            position++;
        }
        return -1;
    }

    public int getQueueSize() {
        return matchQueue.size();
    }

    private void processMatchmaking() {
        cleanupTimeoutRequests();

        while (matchQueue.size() >= 2) {
            List<MatchRequest> matched = findMatchedPlayers();
            if (matched.size() >= 2) {
                createMatchRoom(matched);
            } else {
                break;
            }
        }
    }

    private void cleanupTimeoutRequests() {
        long timeout = gameConfig.getMatchTimeoutSeconds() * 1000L;
        matchQueue.removeIf(request -> {
            if (request.getWaitTime() > timeout) {
                log.info("Match request timeout for player: {}", request.getPlayerId());
                return true;
            }
            return false;
        });
    }

    private List<MatchRequest> findMatchedPlayers() {
        List<MatchRequest> matched = new ArrayList<>();
        List<MatchRequest> candidates = new ArrayList<>(matchQueue);

        int targetSize = Math.min(4, candidates.size());
        if (targetSize < 2) {
            return matched;
        }

        for (int i = 0; i < targetSize; i++) {
            MatchRequest request = candidates.get(i);
            matched.add(request);
        }

        return matched;
    }

    private void createMatchRoom(List<MatchRequest> matchedPlayers) {
        if (matchedPlayers.isEmpty()) {
            return;
        }

        MatchRequest first = matchedPlayers.get(0);
        String roomName = "QuickMatch-" + System.currentTimeMillis();
        com.cardgame.room.entity.Room room = roomManager.createRoom(
                roomName, first.getPlayerId(), 4, false, null);

        room.setStatus(RoomStatus.MATCHING);
        room.setMatchStartTime(System.currentTimeMillis());

        matchedPlayers.forEach(request -> {
            matchQueue.removeIf(r -> r.getPlayerId().equals(request.getPlayerId()));
            Player player = createPlayerFromMatchRequest(request);
            room.addPlayer(player);
        });

        room.setStatus(RoomStatus.WAITING);
        log.info("Created match room {} with {} players", room.getRoomId(), matchedPlayers.size());
    }

    private Player createPlayerFromMatchRequest(MatchRequest request) {
        return Player.builder()
                .playerId(request.getPlayerId())
                .name(request.getPlayerName())
                .playerClass(request.getPlayerClass())
                .maxHp(gameConfig.getBasePlayerHp())
                .currentHp(gameConfig.getBasePlayerHp())
                .baseSpeed(gameConfig.getBasePlayerSpeed())
                .speed(gameConfig.getBasePlayerSpeed())
                .maxEnergy(gameConfig.getDefaultMaxEnergy())
                .currentEnergy(gameConfig.getDefaultMaxEnergy())
                .handLimit(gameConfig.getMaxHandSize())
                .online(true)
                .lastHeartbeat(System.currentTimeMillis())
                .build();
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
