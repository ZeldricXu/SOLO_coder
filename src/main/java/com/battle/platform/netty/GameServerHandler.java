package com.battle.platform.netty;

import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.matching.MatchingEngine;
import com.battle.platform.protocol.GameMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameServerHandler extends SimpleChannelInboundHandler<GameMessage> {

    private final MatchingEngine matchingEngine;
    private final BattlefieldManager battlefieldManager;

    private static final ConcurrentHashMap<Long, Channel> playerChannels = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Channel, Long> channelPlayers = new ConcurrentHashMap<>();

    public static Channel getPlayerChannel(Long playerId) {
        return playerChannels.get(playerId);
    }

    public static ConcurrentHashMap<Long, Channel> getAllPlayerChannels() {
        return playerChannels;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage msg) {
        try {
            switch (msg.getMsgId()) {
                case GameMessage.MSG_MATCH_JOIN -> handleMatchJoin(ctx, msg);
                case GameMessage.MSG_MATCH_LEAVE -> handleMatchLeave(ctx, msg);
                case GameMessage.MSG_BATTLE_JOIN -> handleBattleJoin(ctx, msg);
                case GameMessage.MSG_BATTLE_LEAVE -> handleBattleLeave(ctx, msg);
                case GameMessage.MSG_MOVE -> handleMove(ctx, msg);
                case GameMessage.MSG_SKILL_CAST -> handleSkillCast(ctx, msg);
                case GameMessage.MSG_HEARTBEAT -> handleHeartbeat(ctx, msg);
                default -> log.warn("Unknown message id: {}", msg.getMsgId());
            }
        } catch (Exception e) {
            log.error("Error handling message msgId={} playerId={}", msg.getMsgId(), msg.getPlayerId(), e);
            sendError(ctx, msg.getPlayerId(), e.getMessage());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Long playerId = channelPlayers.remove(ctx.channel());
        if (playerId != null) {
            playerChannels.remove(playerId);
            matchingEngine.leaveMatch(playerId);
            battlefieldManager.playerDisconnect(playerId);
            log.info("Player {} disconnected", playerId);
        }
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            Long playerId = channelPlayers.remove(ctx.channel());
            if (playerId != null) {
                playerChannels.remove(playerId);
                matchingEngine.leaveMatch(playerId);
                battlefieldManager.playerDisconnect(playerId);
            }
            log.warn("Player {} idle timeout, closing connection", playerId);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught for channel {}", ctx.channel().id(), cause);
        ctx.close();
    }

    private void handleMatchJoin(ChannelHandlerContext ctx, GameMessage msg) {
        playerChannels.put(msg.getPlayerId(), ctx.channel());
        channelPlayers.put(ctx.channel(), msg.getPlayerId());
        matchingEngine.joinMatch(msg.getPlayerId());
        log.info("Player {} joined match queue", msg.getPlayerId());
    }

    private void handleMatchLeave(ChannelHandlerContext ctx, GameMessage msg) {
        matchingEngine.leaveMatch(msg.getPlayerId());
        log.info("Player {} left match queue", msg.getPlayerId());
    }

    private void handleBattleJoin(ChannelHandlerContext ctx, GameMessage msg) {
        playerChannels.put(msg.getPlayerId(), ctx.channel());
        channelPlayers.put(ctx.channel(), msg.getPlayerId());
        battlefieldManager.playerJoin(msg.getPlayerId(), ctx.channel());
    }

    private void handleBattleLeave(ChannelHandlerContext ctx, GameMessage msg) {
        battlefieldManager.playerLeave(msg.getPlayerId());
    }

    private void handleMove(ChannelHandlerContext ctx, GameMessage msg) {
        battlefieldManager.playerMove(msg.getPlayerId(), msg.getPayload());
    }

    private void handleSkillCast(ChannelHandlerContext ctx, GameMessage msg) {
        battlefieldManager.playerSkillCast(msg.getPlayerId(), msg.getPayload());
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, GameMessage msg) {
        GameMessage response = GameMessage.builder()
                .msgId(GameMessage.MSG_HEARTBEAT)
                .msgType(GameMessage.TYPE_RESPONSE)
                .playerId(msg.getPlayerId())
                .timestamp(System.currentTimeMillis())
                .build();
        ctx.writeAndFlush(response);
    }

    private void sendError(ChannelHandlerContext ctx, long playerId, String error) {
        GameMessage errMsg = GameMessage.builder()
                .msgId(GameMessage.MSG_ERROR)
                .msgType(GameMessage.TYPE_PUSH)
                .playerId(playerId)
                .timestamp(System.currentTimeMillis())
                .payload(error.getBytes())
                .build();
        ctx.writeAndFlush(errMsg);
    }

    public void sendToPlayer(Long playerId, GameMessage msg) {
        Channel ch = playerChannels.get(playerId);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(msg);
        }
    }

    public void broadcastToPlayers(Iterable<Long> playerIds, GameMessage msg) {
        for (Long pid : playerIds) {
            sendToPlayer(pid, msg);
        }
    }
}
