package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.AOIGrid;
import com.battle.platform.battlefield.PlayerPosition;
import com.battle.platform.battlefield.event.*;
import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.protocol.GameMessage;
import com.battle.platform.replay.ReplayRecorder;
import com.google.common.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SyncLayer {

    private final String battleId;
    private final BattlefieldEventBus eventBus;
    private final BattlefieldProperties properties;
    private final ConnectionLayer connectionLayer;
    private final AOIGrid aoiGrid;
    private final ReplayRecorder replayRecorder;

    private final Map<Long, PlayerPosition> playerPositions = new ConcurrentHashMap<>();

    public SyncLayer(String battleId, BattlefieldEventBus eventBus,
                     BattlefieldProperties properties, ConnectionLayer connectionLayer,
                     AOIGrid aoiGrid, ReplayRecorder replayRecorder) {
        this.battleId = battleId;
        this.eventBus = eventBus;
        this.properties = properties;
        this.connectionLayer = connectionLayer;
        this.aoiGrid = aoiGrid;
        this.replayRecorder = replayRecorder;
    }

    public void onPlayerMove(Long playerId, double x, double y, double z, float rotation) {
        PlayerPosition oldPos = playerPositions.get(playerId);
        if (oldPos == null) return;

        PlayerPosition newPos = PlayerPosition.builder()
                .playerId(playerId)
                .x(x)
                .y(y)
                .z(z)
                .rotation(rotation)
                .timestamp(System.currentTimeMillis())
                .build();

        aoiGrid.updatePlayer(playerId, oldPos.getX(), oldPos.getZ(), x, z);
        playerPositions.put(playerId, newPos);

        Set<Long> nearby = aoiGrid.getNearbyPlayers(x, z);
        nearby.remove(playerId);

        byte[] movePayload = buildMovePayload(playerId, newPos);
        GameMessage moveMsg = GameMessage.builder()
                .msgId(GameMessage.MSG_MOVE)
                .msgType(GameMessage.TYPE_PUSH)
                .playerId(playerId)
                .timestamp(System.currentTimeMillis())
                .payload(movePayload)
                .build();

        connectionLayer.broadcastToPlayers(nearby, moveMsg);

        eventBus.post(new PlayerMoveEvent(battleId, playerId, newPos));

        if (replayRecorder != null) {
            replayRecorder.recordMoveEvent(battleId, playerId, newPos);
        }
    }

    @Subscribe
    public void onPlayerConnected(PlayerConnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;

        PlayerPosition pos = PlayerPosition.builder()
                .playerId(event.getPlayerId())
                .x(0)
                .y(0)
                .z(0)
                .rotation(0)
                .timestamp(System.currentTimeMillis())
                .build();
        playerPositions.put(event.getPlayerId(), pos);
        aoiGrid.addPlayer(event.getPlayerId(), 0, 0);
    }

    @Subscribe
    public void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;
        log.debug("SyncLayer noted player {} disconnected in battle {}", event.getPlayerId(), battleId);
    }

    @Subscribe
    public void onPlayerReconnected(PlayerReconnectedEvent event) {
        if (!event.getBattleId().equals(battleId)) return;

        PlayerPosition pos = playerPositions.get(event.getPlayerId());
        if (pos != null && connectionLayer.isConnected(event.getPlayerId())) {
            byte[] posPayload = buildMovePayload(event.getPlayerId(), pos);
            GameMessage syncMsg = GameMessage.builder()
                    .msgId(GameMessage.MSG_BATTLE_STATE)
                    .msgType(GameMessage.TYPE_PUSH)
                    .playerId(event.getPlayerId())
                    .timestamp(System.currentTimeMillis())
                    .payload(posPayload)
                    .build();
            connectionLayer.sendToPlayer(event.getPlayerId(), syncMsg);
        }
    }

    @Subscribe
    public void onSkillCast(SkillCastEvent event) {
        if (!event.getBattleId().equals(battleId)) return;

        PlayerPosition pos = playerPositions.get(event.getPlayerId());
        if (pos == null) return;

        Set<Long> nearby = aoiGrid.getNearbyPlayers(pos.getX(), pos.getZ());

        byte[] skillPayload = buildSkillPayload(event.getPlayerId(), event.getSkillId(), event.getTargetX(), event.getTargetZ());
        GameMessage skillMsg = GameMessage.builder()
                .msgId(GameMessage.MSG_SKILL_CAST)
                .msgType(GameMessage.TYPE_PUSH)
                .playerId(event.getPlayerId())
                .timestamp(System.currentTimeMillis())
                .payload(skillPayload)
                .build();

        connectionLayer.broadcastToPlayers(nearby, skillMsg);
    }

    @Subscribe
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!event.getBattleId().equals(battleId)) return;

        byte[] deathPayload = (event.getKillerId() + "," + event.getVictimId()).getBytes();
        GameMessage msg = GameMessage.builder()
                .msgId(GameMessage.MSG_DEATH)
                .msgType(GameMessage.TYPE_PUSH)
                .timestamp(System.currentTimeMillis())
                .payload(deathPayload)
                .build();
        connectionLayer.broadcastToAll(msg);
    }

    @Subscribe
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!event.getBattleId().equals(battleId)) return;

        GameMessage msg = GameMessage.builder()
                .msgId(GameMessage.MSG_RESPAWN)
                .msgType(GameMessage.TYPE_PUSH)
                .playerId(event.getPlayerId())
                .timestamp(System.currentTimeMillis())
                .build();
        connectionLayer.broadcastToAll(msg);
    }

    public void removePlayer(Long playerId) {
        PlayerPosition pos = playerPositions.remove(playerId);
        if (pos != null) {
            aoiGrid.removePlayer(playerId, pos.getX(), pos.getZ());
        }
    }

    public PlayerPosition getPlayerPosition(Long playerId) {
        return playerPositions.get(playerId);
    }

    public Set<Long> getNearbyPlayerIds(Long playerId) {
        PlayerPosition pos = playerPositions.get(playerId);
        if (pos == null) return Collections.emptySet();
        Set<Long> nearby = aoiGrid.getNearbyPlayers(pos.getX(), pos.getZ());
        nearby.remove(playerId);
        return nearby;
    }

    private byte[] buildMovePayload(Long playerId, PlayerPosition pos) {
        return (playerId + "," + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + pos.getRotation()).getBytes();
    }

    private byte[] buildSkillPayload(Long playerId, int skillId, double targetX, double targetZ) {
        return (playerId + "," + skillId + "," + targetX + "," + targetZ).getBytes();
    }
}
