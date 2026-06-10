package com.cardgame.netty.dispatcher;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.netty.session.ChannelManager;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.netty.validation.MessageValidationChain;
import com.cardgame.netty.validation.ValidationResult;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class MessageDispatcher {
    private final Map<MessageType, MessageHandler> handlerMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    @Autowired
    private ChannelManager channelManager;

    @Autowired
    private MessageValidationChain validationChain;

    public void registerHandler(MessageHandler handler) {
        handlerMap.put(handler.getType(), handler);
    }

    public void dispatch(Channel channel, GameMessage message) {
        PlayerSession session = channelManager.getSession(channel);
        if (session == null) {
            session = channelManager.createSession(channel);
        }
        session.updateLastActiveTime();

        MessageHandler handler = handlerMap.get(message.getType());
        if (handler == null) {
            log.warn("No handler found for message type: {}", message.getType());
            sendError(session, message, 404, "No handler for message type: " + message.getType());
            return;
        }

        ValidationResult validationResult = validationChain.validate(message);
        if (!validationResult.isValid()) {
            log.warn("Message validation failed for type {}: {}", message.getType(), validationResult.getFirstError());
            sendError(session, message, validationResult.getErrorCode(), validationResult.getFirstError());
            return;
        }

        if (requiresAuthentication(message.getType()) && !session.isAuthenticated()) {
            sendError(session, message, 401, "Authentication required");
            return;
        }

        executorService.submit(() -> {
            try {
                handler.handle(session, message);
            } catch (Exception e) {
                log.error("Error handling message type: {}", message.getType(), e);
                sendError(session, message, 500, "Internal server error: " + e.getMessage());
            }
        });
    }

    private boolean requiresAuthentication(MessageType type) {
        return type != MessageType.LOGIN_REQ && type != MessageType.HEARTBEAT && type != MessageType.RECONNECT_REQ;
    }

    private void sendError(PlayerSession session, GameMessage request, int code, String message) {
        GameMessage response = GameMessage.createResponse(request, code, message, null);
        if (session.getChannel() != null && session.getChannel().isActive()) {
            session.getChannel().writeAndFlush(response);
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
