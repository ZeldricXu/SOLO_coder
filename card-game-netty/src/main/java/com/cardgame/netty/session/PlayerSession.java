package com.cardgame.netty.session;

import io.netty.channel.Channel;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class PlayerSession {
    private String sessionId;
    private String playerId;
    private String accountId;
    private String roomId;
    private Channel channel;
    private long createTime;
    private long lastActiveTime;
    private boolean authenticated;

    public PlayerSession(Channel channel) {
        this.channel = channel;
        this.sessionId = channel.id().asLongText();
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public String getRemoteAddress() {
        if (channel != null && channel.remoteAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        }
        return "unknown";
    }

    public void updateLastActiveTime() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    public boolean isChannelActive() {
        return channel != null && channel.isActive();
    }

    public void close() {
        if (channel != null) {
            channel.close();
        }
    }
}
