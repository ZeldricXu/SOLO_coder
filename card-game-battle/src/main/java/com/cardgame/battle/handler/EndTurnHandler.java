package com.cardgame.battle.handler;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.battle.engine.BattleEngine;
import com.cardgame.battle.service.BattleSyncService;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.manager.RedisRoomStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EndTurnHandler implements MessageHandler {

    @Autowired
    private BattleEngine battleEngine;

    @Autowired
    private BattleSyncService battleSyncService;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Override
    public MessageType getType() {
        return MessageType.END_TURN_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        String playerId = session.getPlayerId();
        String roomId = redisRoomStateManager.getPlayerRoomId(playerId);

        if (roomId == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Not in a room", null));
            return;
        }

        BattleContext context = battleEngine.getBattleByRoomId(roomId);
        if (context == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 404, "No active battle", null));
            return;
        }

        BattleAction action = battleEngine.endTurn(context.getBattleId(), playerId);

        if (action == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Failed to end turn", null));
            return;
        }

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(action))
        );

        battleSyncService.syncBattleAction(context.getBattleId(), action);

        if (context.isBattleOver()) {
            battleSyncService.syncBattleEnd(context.getBattleId());
        }
    }
}
