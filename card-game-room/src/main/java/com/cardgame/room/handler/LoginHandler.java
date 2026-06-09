package com.cardgame.room.handler;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.protocol.request.LoginRequest;
import com.cardgame.common.protocol.response.LoginResponse;
import com.cardgame.common.utils.IdGenerator;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.manager.RedisRoomStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoginHandler implements MessageHandler {

    @Autowired
    private ChannelManager channelManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private GameConfig gameConfig;

    @Override
    public MessageType getType() {
        return MessageType.LOGIN_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        LoginRequest loginRequest = JsonUtils.fromJson(request.getData(), LoginRequest.class);
        if (loginRequest == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Invalid request", null));
            return;
        }

        String playerId = IdGenerator.generatePlayerId();
        String token = IdGenerator.generateUUID();

        Player player = Player.builder()
                .playerId(playerId)
                .accountId(loginRequest.getAccountId())
                .name(loginRequest.getPlayerName())
                .playerClass(PlayerClass.WARRIOR)
                .maxHp(gameConfig.getBasePlayerHp())
                .currentHp(gameConfig.getBasePlayerHp())
                .baseSpeed(gameConfig.getBasePlayerSpeed())
                .speed(gameConfig.getBasePlayerSpeed())
                .maxEnergy(gameConfig.getDefaultMaxEnergy())
                .currentEnergy(gameConfig.getDefaultMaxEnergy())
                .handLimit(gameConfig.getMaxHandSize())
                .gold(100)
                .floor(1)
                .online(true)
                .lastHeartbeat(System.currentTimeMillis())
                .build();

        channelManager.bindPlayer(playerId, session);
        session.setAccountId(loginRequest.getAccountId());
        redisRoomStateManager.setPlayerOnline(playerId, true);

        LoginResponse response = LoginResponse.builder()
                .playerId(playerId)
                .token(token)
                .playerLevel(1)
                .totalGames(0)
                .wins(0)
                .build();

        log.info("Player logged in: {} - {}", playerId, loginRequest.getPlayerName());

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(response))
        );
    }
}
