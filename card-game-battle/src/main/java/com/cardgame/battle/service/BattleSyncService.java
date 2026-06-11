package com.cardgame.battle.service;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.battle.engine.BattleEngine;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.room.manager.RoomManager;
import com.cardgame.room.service.RoomSyncService;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BattleSyncService {

    @Autowired
    private BattleEngine battleEngine;

    @Autowired
    private ChannelManager channelManager;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RoomSyncService roomSyncService;

    public void syncBattleStatus(String battleId) {
        BattleContext context = battleEngine.getBattle(battleId);
        if (context == null) {
            return;
        }

        String battleData = JsonUtils.toJson(context);
        GameMessage message = GameMessage.createPush(
                MessageType.BATTLE_STATUS_SYNC,
                context.getRoomId(),
                null,
                battleData
        );

        broadcastToBattle(context, message);
    }

    public void syncBattleAction(String battleId, BattleAction action) {
        BattleContext context = battleEngine.getBattle(battleId);
        if (context == null) {
            return;
        }

        String actionData = JsonUtils.toJson(action);
        GameMessage message = GameMessage.createPush(
                MessageType.BATTLE_STATUS_SYNC,
                context.getRoomId(),
                null,
                actionData
        );

        broadcastToBattle(context, message);
    }

    public void syncBattleEnd(String battleId) {
        BattleContext context = battleEngine.getBattle(battleId);
        if (context == null) {
            return;
        }

        String endData = JsonUtils.toJson(context);
        GameMessage message = GameMessage.createPush(
                MessageType.BATTLE_END_SYNC,
                context.getRoomId(),
                null,
                endData
        );

        broadcastToBattle(context, message);
    }

    private void broadcastToBattle(BattleContext context, GameMessage message) {
        for (com.cardgame.common.entity.Player player : context.getPlayers()) {
            if (player.isOnline()) {
                Channel channel = channelManager.getChannelByPlayerId(player.getPlayerId());
                if (channel != null && channel.isActive()) {
                    channel.writeAndFlush(message);
                }
            }
        }
    }

    public void sendBattleState(String playerId, String battleId) {
        BattleContext context = battleEngine.getBattle(battleId);
        if (context == null) {
            return;
        }

        String battleData = JsonUtils.toJson(context);
        GameMessage message = GameMessage.createPush(
                MessageType.BATTLE_STATUS_SYNC,
                context.getRoomId(),
                playerId,
                battleData
        );

        Channel channel = channelManager.getChannelByPlayerId(playerId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(message);
        }
    }
}
