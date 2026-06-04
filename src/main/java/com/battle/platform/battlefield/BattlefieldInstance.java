package com.battle.platform.battlefield;

import com.battle.platform.battlefield.event.BattlefieldEventBus;
import com.battle.platform.battlefield.layer.ConnectionLayer;
import com.battle.platform.battlefield.layer.LogicLayer;
import com.battle.platform.battlefield.layer.SyncLayer;
import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.score.ScoreService;
import com.battle.platform.replay.ReplayRecorder;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Slf4j
public class BattlefieldInstance {

    private String battleId;
    private int battlefieldId;
    private BattlefieldProperties properties;
    private ScoreService scoreService;
    private ReplayRecorder replayRecorder;

    private final BattlefieldEventBus eventBus;
    private ConnectionLayer connectionLayer;
    private SyncLayer syncLayer;
    private LogicLayer logicLayer;

    private long startTimeMs;

    public BattlefieldInstance() {
        this.eventBus = new BattlefieldEventBus();
    }

    public void init(String battleId, int battlefieldId, BattlefieldProperties properties,
                     ScoreService scoreService, ReplayRecorder replayRecorder) {
        this.battleId = battleId;
        this.battlefieldId = battlefieldId;
        this.properties = properties;
        this.scoreService = scoreService;
        this.replayRecorder = replayRecorder;
        this.startTimeMs = System.currentTimeMillis();

        AOIGrid aoiGrid = new AOIGrid(properties);

        this.connectionLayer = new ConnectionLayer(battleId, eventBus, properties);
        this.syncLayer = new SyncLayer(battleId, eventBus, properties, connectionLayer, aoiGrid, replayRecorder);
        this.logicLayer = new LogicLayer(battleId, eventBus, scoreService, connectionLayer, replayRecorder);

        eventBus.register(connectionLayer);
        eventBus.register(syncLayer);
        eventBus.register(logicLayer);
    }

    @Deprecated
    public void init(String battleId, int battlefieldId, BattlefieldProperties properties,
                     com.battle.platform.score.ScoreEngine scoreEngine, ReplayRecorder replayRecorder,
                     com.battle.platform.netty.GameServerHandler gameServerHandler) {
        this.init(battleId, battlefieldId, properties, (ScoreService) null, replayRecorder);
    }

    public void addPlayer(Long playerId, Channel channel) {
        connectionLayer.onPlayerConnect(playerId, channel);
        log.info("Player {} added to battlefield {}", playerId, battleId);
    }

    public void removePlayer(Long playerId) {
        connectionLayer.removePlayer(playerId);
        syncLayer.removePlayer(playerId);
        logicLayer.removePlayer(playerId);
        log.info("Player {} removed from battlefield {}", playerId, battleId);
    }

    public void handleMove(Long playerId, byte[] payload) {
        String[] parts = new String(payload).split(",");
        if (parts.length < 4) return;

        double newX = Double.parseDouble(parts[0]);
        double newY = Double.parseDouble(parts[1]);
        double newZ = Double.parseDouble(parts[2]);
        float rotation = Float.parseFloat(parts[3]);

        syncLayer.onPlayerMove(playerId, newX, newY, newZ, rotation);
    }

    public void handleSkillCast(Long playerId, byte[] payload) {
        String[] parts = new String(payload).split(",");
        if (parts.length < 3) return;

        int skillId = Integer.parseInt(parts[0]);
        double targetX = Double.parseDouble(parts[1]);
        double targetZ = Double.parseDouble(parts[2]);

        logicLayer.onSkillCast(playerId, skillId, targetX, targetZ);
    }

    public void handleDeath(Long killerId, Long victimId, int skillId) {
        logicLayer.onDeath(killerId, victimId, skillId);
    }

    public void handleCapture(Long playerId, int pointId) {
        logicLayer.onCapture(playerId, pointId);
    }

    public void playerDisconnect(Long playerId) {
        connectionLayer.onPlayerDisconnect(playerId, "client_disconnect");
    }

    public boolean playerReconnect(Long playerId, String token, Channel channel) {
        return connectionLayer.onReconnect(playerId, token, channel);
    }

    public void endBattle() {
        logicLayer.endBattle();
    }

    public int getPlayerCount() {
        return connectionLayer.getConnectedCount();
    }

    public boolean isActive() {
        return logicLayer != null && logicLayer.isActive();
    }

    public Map<Long, Channel> getPlayerChannels() {
        Map<Long, Channel> channels = new ConcurrentHashMap<>();
        if (connectionLayer != null) {
            for (Long pid : connectionLayer.getConnectedPlayers()) {
                Channel ch = connectionLayer.getPlayerChannel(pid);
                if (ch != null) channels.put(pid, ch);
            }
        }
        return channels;
    }

    public Map<Long, PlayerPosition> getPlayerPositions() {
        Map<Long, PlayerPosition> positions = new ConcurrentHashMap<>();
        if (syncLayer != null) {
            for (Long pid : connectionLayer.getConnectedPlayers()) {
                PlayerPosition pos = syncLayer.getPlayerPosition(pid);
                if (pos != null) positions.put(pid, pos);
            }
        }
        return positions;
    }

    public void checkHeartbeatTimeouts() {
        if (connectionLayer != null) {
            connectionLayer.checkHeartbeatTimeouts();
        }
    }
}
