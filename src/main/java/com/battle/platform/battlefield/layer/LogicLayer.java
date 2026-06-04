package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.event.*;
import com.battle.platform.replay.ReplayRecorder;
import com.battle.platform.score.ScoreService;
import com.google.common.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class LogicLayer {

    private final String battleId;
    private final BattlefieldEventBus eventBus;
    private final ScoreService scoreService;
    private final ConnectionLayer connectionLayer;
    private final ReplayRecorder replayRecorder;

    private final Set<Long> alivePlayers = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> respawnTimers = new ConcurrentHashMap<>();
    private volatile boolean active = true;
    private Timer respawnTimer;

    public LogicLayer(String battleId, BattlefieldEventBus eventBus,
                      ScoreService scoreService, ConnectionLayer connectionLayer,
                      ReplayRecorder replayRecorder) {
        this.battleId = battleId;
        this.eventBus = eventBus;
        this.scoreService = scoreService;
        this.connectionLayer = connectionLayer;
        this.replayRecorder = replayRecorder;
        this.respawnTimer = new Timer("respawn-timer-" + battleId, true);
    }

    @Subscribe
    public void onPlayerConnected(PlayerConnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;
        alivePlayers.add(event.getPlayerId());
        scoreService.initPlayerScore(battleId, event.getPlayerId());
        log.info("LogicLayer: player {} added to battle {}", event.getPlayerId(), battleId);
    }

    @Subscribe
    public void onPlayerReconnected(PlayerReconnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;
        log.info("LogicLayer: player {} reconnected in battle {}", event.getPlayerId(), battleId);
    }

    @Subscribe
    public void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;
        if (alivePlayers.size() <= 1 && connectedAndAliveCount() <= 1) {
            log.info("Battle {} ending: last player disconnected", battleId);
        }
    }

    public void onSkillCast(Long playerId, int skillId, double targetX, double targetZ) {
        if (!alivePlayers.contains(playerId)) return;
        eventBus.post(new SkillCastEvent(battleId, playerId, skillId, targetX, targetZ));

        if (replayRecorder != null) {
            replayRecorder.recordSkillEvent(battleId, playerId, skillId, targetX, targetZ);
        }
    }

    public void onDamage(Long attackerId, Long victimId, double damage, int skillId, boolean isHeadshot) {
        if (!alivePlayers.contains(attackerId)) return;

        scoreService.onDamage(battleId, attackerId, victimId, damage, skillId, isHeadshot);
        eventBus.post(new DamageEvent(battleId, attackerId, victimId, damage, skillId, isHeadshot));
    }

    public void onDeath(Long killerId, Long victimId, int skillId) {
        alivePlayers.remove(victimId);

        scoreService.onKill(battleId, killerId, victimId, skillId);

        eventBus.post(new PlayerDeathEvent(battleId, killerId, victimId, skillId));

        if (replayRecorder != null) {
            replayRecorder.recordDeathEvent(battleId, killerId, victimId, skillId);
        }

        scheduleRespawn(victimId);
    }

    public void onCapture(Long playerId, int pointId) {
        scoreService.onCapture(battleId, playerId, pointId);

        eventBus.post(new CapturePointEvent(battleId, playerId, pointId));

        if (replayRecorder != null) {
            replayRecorder.recordCaptureEvent(battleId, playerId, pointId);
        }
    }

    private void scheduleRespawn(Long playerId) {
        int respawnDelay = 5000;
        respawnTimers.put(playerId, respawnDelay);

        respawnTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!active) return;
                alivePlayers.add(playerId);
                respawnTimers.remove(playerId);

                eventBus.post(new PlayerRespawnEvent(battleId, playerId));

                if (replayRecorder != null) {
                    replayRecorder.recordRespawnEvent(battleId, playerId);
                }
            }
        }, respawnDelay);
    }

    public void endBattle() {
        active = false;
        respawnTimer.cancel();
        scoreService.finalizeBattleScores(battleId);

        if (replayRecorder != null) {
            replayRecorder.flushBattleReplay(battleId);
        }

        log.info("Battle {} ended", battleId);
    }

    public void removePlayer(Long playerId) {
        alivePlayers.remove(playerId);
        respawnTimers.remove(playerId);
    }

    public boolean isAlive(Long playerId) {
        return alivePlayers.contains(playerId);
    }

    public boolean isActive() {
        return active;
    }

    public Set<Long> getAlivePlayers() {
        return Collections.unmodifiableSet(alivePlayers);
    }

    private int connectedAndAliveCount() {
        int count = 0;
        for (Long pid : alivePlayers) {
            if (connectionLayer.isConnected(pid)) count++;
        }
        return count;
    }
}
