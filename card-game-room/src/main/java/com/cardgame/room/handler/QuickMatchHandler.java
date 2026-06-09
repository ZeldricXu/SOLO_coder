package com.cardgame.room.handler;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.manager.MatchmakingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class QuickMatchHandler implements MessageHandler {

    @Autowired
    private MatchmakingManager matchmakingManager;

    @Override
    public MessageType getType() {
        return MessageType.QUICK_MATCH_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        String playerId = session.getPlayerId();
        Map<String, Object> data = JsonUtils.fromJson(request.getData(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

        PlayerClass playerClass = PlayerClass.WARRIOR;
        if (data != null && data.get("playerClass") != null) {
            playerClass = PlayerClass.valueOf((String) data.get("playerClass"));
        }

        matchmakingManager.addToMatchQueue(playerId, "Player-" + playerId, playerClass, 1);

        Map<String, Object> response = new HashMap<>();
        response.put("queuePosition", matchmakingManager.getQueuePosition(playerId));
        response.put("queueSize", matchmakingManager.getQueueSize());

        log.info("Player {} joined quick match queue", playerId);

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(response))
        );
    }
}
