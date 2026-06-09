package com.cardgame.netty.dispatcher;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.netty.session.PlayerSession;

public interface MessageHandler {
    MessageType getType();

    void handle(PlayerSession session, GameMessage request) throws Exception;
}
