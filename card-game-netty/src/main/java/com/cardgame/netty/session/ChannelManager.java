package com.cardgame.netty.session;

import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChannelManager {
    private final Map<Channel, PlayerSession> channelSessionMap = new ConcurrentHashMap<>();
    private final Map<String, PlayerSession> playerSessionMap = new ConcurrentHashMap<>();
    private final Map<String, Channel> playerChannelMap = new ConcurrentHashMap<>();

    public PlayerSession createSession(Channel channel) {
        PlayerSession session = new PlayerSession(channel);
        channelSessionMap.put(channel, session);
        return session;
    }

    public PlayerSession getSession(Channel channel) {
        return channelSessionMap.get(channel);
    }

    public PlayerSession getSessionByPlayerId(String playerId) {
        return playerSessionMap.get(playerId);
    }

    public Channel getChannelByPlayerId(String playerId) {
        return playerChannelMap.get(playerId);
    }

    public void bindPlayer(String playerId, PlayerSession session) {
        session.setPlayerId(playerId);
        session.setAuthenticated(true);
        playerSessionMap.put(playerId, session);
        playerChannelMap.put(playerId, session.getChannel());
    }

    public void unbindPlayer(String playerId) {
        PlayerSession session = playerSessionMap.remove(playerId);
        if (session != null) {
            playerChannelMap.remove(playerId);
            session.setAuthenticated(false);
            session.setPlayerId(null);
        }
    }

    public void removeSession(Channel channel) {
        PlayerSession session = channelSessionMap.remove(channel);
        if (session != null && session.getPlayerId() != null) {
            unbindPlayer(session.getPlayerId());
        }
    }

    public int getOnlineCount() {
        return playerSessionMap.size();
    }

    public boolean isPlayerOnline(String playerId) {
        return playerSessionMap.containsKey(playerId) && getChannelByPlayerId(playerId).isActive();
    }

    public void updateRoomId(String playerId, String roomId) {
        PlayerSession session = playerSessionMap.get(playerId);
        if (session != null) {
            session.setRoomId(roomId);
        }
    }
}
