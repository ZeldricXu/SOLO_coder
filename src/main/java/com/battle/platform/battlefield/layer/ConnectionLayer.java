package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.event.*;
import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.protocol.GameMessage;
import com.google.common.eventbus.Subscribe;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ConnectionLayer {

    private final String battleId;
    private final BattlefieldEventBus eventBus;
    private final BattlefieldProperties properties;

    private final Map<Long, Channel> playerChannels = new ConcurrentHashMap<>();
    private final Map<Channel, Long> channelPlayers = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    private final Map<Long, String> reconnectTokens = new ConcurrentHashMap<>();
    private final Set<Long> connectedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<Long> disconnectedPlayers = ConcurrentHashMap.newKeySet();

    public ConnectionLayer(String battleId, BattlefieldEventBus eventBus, BattlefieldProperties properties) {
        this.battleId = battleId;
        this.eventBus = eventBus;
        this.properties = properties;
    }

    public void onPlayerConnect(Long playerId, Channel channel) {
        playerChannels.put(playerId, channel);
        channelPlayers.put(channel, playerId);
        lastHeartbeatTime.put(playerId, System.currentTimeMillis());
        connectedPlayers.add(playerId);
        disconnectedPlayers.remove(playerId);

        reconnectTokens.put(playerId, UUID.randomUUID().toString());

        eventBus.post(new PlayerConnectedEvent(battleId, playerId, channel));
        log.info("Player {} connected to battle {}", playerId, battleId);
    }

    public boolean onReconnect(Long playerId, String token, Channel channel) {
        String expectedToken = reconnectTokens.get(playerId);
        if (expectedToken == null || !expectedToken.equals(token)) {
            log.warn("Invalid reconnect token for player {} in battle {}", playerId, battleId);
            return false;
        }

        if (!disconnectedPlayers.contains(playerId)) {
            log.warn("Player {} not in disconnected state, cannot reconnect", playerId);
            return false;
        }

        Channel oldChannel = playerChannels.remove(playerId);
        if (oldChannel != null) {
            channelPlayers.remove(oldChannel);
        }

        playerChannels.put(playerId, channel);
        channelPlayers.put(channel, playerId);
        lastHeartbeatTime.put(playerId, System.currentTimeMillis());
        connectedPlayers.add(playerId);
        disconnectedPlayers.remove(playerId);

        reconnectTokens.put(playerId, UUID.randomUUID().toString());

        eventBus.post(new PlayerReconnectedEvent(battleId, playerId, channel));
        log.info("Player {} reconnected to battle {}", playerId, battleId);
        return true;
    }

    public void onPlayerDisconnect(Long playerId, String reason) {
        Channel ch = playerChannels.remove(playerId);
        if (ch != null) {
            channelPlayers.remove(ch);
        }
        connectedPlayers.remove(playerId);
        disconnectedPlayers.add(playerId);
        lastHeartbeatTime.remove(playerId);

        eventBus.post(new PlayerDisconnectedEvent(battleId, playerId, reason));
        log.info("Player {} disconnected from battle {}, reason: {}", playerId, battleId, reason);
    }

    public void onHeartbeat(Long playerId) {
        if (connectedPlayers.contains(playerId)) {
            lastHeartbeatTime.put(playerId, System.currentTimeMillis());
        }
    }

    public void checkHeartbeatTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = properties.getIdleTimeoutMs();

        for (Long playerId : connectedPlayers) {
            Long lastTime = lastHeartbeatTime.get(playerId);
            if (lastTime != null && (now - lastTime) > timeoutMs) {
                log.warn("Player {} heartbeat timeout in battle {}", playerId, battleId);
                eventBus.post(new HeartbeatTimeoutEvent(battleId, playerId));
                onPlayerDisconnect(playerId, "heartbeat_timeout");
            }
        }
    }

    public void removePlayer(Long playerId) {
        Channel ch = playerChannels.remove(playerId);
        if (ch != null) channelPlayers.remove(ch);
        connectedPlayers.remove(playerId);
        disconnectedPlayers.remove(playerId);
        lastHeartbeatTime.remove(playerId);
        reconnectTokens.remove(playerId);
    }

    @Subscribe
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!event.getBattleId().equals(battleId)) return;
    }

    public void sendToPlayer(Long playerId, GameMessage msg) {
        Channel ch = playerChannels.get(playerId);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg);
        }
    }

    public void broadcastToAll(GameMessage msg) {
        for (Map.Entry<Long, Channel> e : playerChannels.entrySet()) {
            if (e.getValue().isActive()) {
                e.getValue().writeAndFlush(msg);
            }
        }
    }

    public void broadcastToPlayers(Iterable<Long> playerIds, GameMessage msg) {
        for (Long pid : playerIds) {
            sendToPlayer(pid, msg);
        }
    }

    public Channel getPlayerChannel(Long playerId) {
        return playerChannels.get(playerId);
    }

    public boolean isConnected(Long playerId) {
        return connectedPlayers.contains(playerId);
    }

    public boolean isDisconnected(Long playerId) {
        return disconnectedPlayers.contains(playerId);
    }

    public String getReconnectToken(Long playerId) {
        return reconnectTokens.get(playerId);
    }

    public Set<Long> getConnectedPlayers() {
        return Collections.unmodifiableSet(connectedPlayers);
    }

    public Set<Long> getDisconnectedPlayers() {
        return Collections.unmodifiableSet(disconnectedPlayers);
    }

    public int getConnectedCount() {
        return connectedPlayers.size();
    }

    public String getBattleId() {
        return battleId;
    }
}
