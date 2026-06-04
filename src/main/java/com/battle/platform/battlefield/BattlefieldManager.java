package com.battle.platform.battlefield;

import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.score.ScoreService;
import com.battle.platform.replay.ReplayRecorder;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattlefieldManager {

    private final BattlefieldProperties properties;
    private final ScoreService scoreService;
    private final ReplayRecorder replayRecorder;

    private final ConcurrentHashMap<String, BattlefieldInstance> battlefields = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> playerBattleMap = new ConcurrentHashMap<>();
    private final AtomicInteger battlefieldIdCounter = new AtomicInteger(1);

    public String createBattlefield(List<Long> playerIds) {
        String battleId = "BF-" + UUID.randomUUID().toString().substring(0, 8);
        int bfId = battlefieldIdCounter.getAndIncrement();

        BattlefieldInstance instance = new BattlefieldInstance();
        instance.init(battleId, bfId, properties, scoreService, replayRecorder);
        battlefields.put(battleId, instance);

        for (Long pid : playerIds) {
            playerBattleMap.put(pid, battleId);
        }

        log.info("Created battlefield {} for {} players", battleId, playerIds.size());
        return battleId;
    }

    public void playerJoin(Long playerId, Channel channel) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) {
            log.warn("Player {} has no assigned battlefield", playerId);
            return;
        }

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null) {
            bf.addPlayer(playerId, channel);
        }
    }

    public void playerLeave(Long playerId) {
        String battleId = playerBattleMap.remove(playerId);
        if (battleId == null) return;

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null) {
            bf.removePlayer(playerId);
            if (bf.getPlayerCount() == 0) {
                bf.endBattle();
                battlefields.remove(battleId);
                log.info("Battlefield {} removed (no players)", battleId);
            }
        }
    }

    public void playerDisconnect(Long playerId) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) {
            playerLeave(playerId);
            return;
        }

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null) {
            bf.playerDisconnect(playerId);
        }
    }

    public boolean playerReconnect(Long playerId, String token, Channel channel) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) return false;

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null) {
            return bf.playerReconnect(playerId, token, channel);
        }
        return false;
    }

    public void playerMove(Long playerId, byte[] payload) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) return;

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null && bf.isActive()) {
            bf.handleMove(playerId, payload);
        }
    }

    public void playerSkillCast(Long playerId, byte[] payload) {
        String battleId = playerBattleMap.get(playerId);
        if (battleId == null) return;

        BattlefieldInstance bf = battlefields.get(battleId);
        if (bf != null && bf.isActive()) {
            bf.handleSkillCast(playerId, payload);
        }
    }

    public void endBattle(String battleId) {
        BattlefieldInstance bf = battlefields.remove(battleId);
        if (bf != null) {
            bf.endBattle();
            bf.getPlayerPositions().keySet().forEach(playerBattleMap::remove);
        }
    }

    public BattlefieldInstance getBattlefield(String battleId) {
        return battlefields.get(battleId);
    }

    public int getActiveBattlefieldCount() {
        return battlefields.size();
    }

    public Map<String, Integer> getBattlefieldStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (Map.Entry<String, BattlefieldInstance> e : battlefields.entrySet()) {
            stats.put(e.getKey(), e.getValue().getPlayerCount());
        }
        return stats;
    }
}
