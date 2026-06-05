package com.company.dbstudio.core;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);
    private static final EventBus INSTANCE = new EventBus();

    private final Map<Class<?>, List<EventHandler<?>>> handlers = new ConcurrentHashMap<>();
    private volatile boolean shutdown = false;

    private EventBus() {
    }

    public static EventBus getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        if (shutdown) {
            throw new IllegalStateException("EventBus is shutdown");
        }
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new EventHandler<>(handler, false));
        logger.debug("Subscribed to event: {}", eventType.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public <T> void subscribeOnFxThread(Class<T> eventType, Consumer<T> handler) {
        if (shutdown) {
            throw new IllegalStateException("EventBus is shutdown");
        }
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new EventHandler<>(handler, true));
        logger.debug("Subscribed to event (FX thread): {}", eventType.getSimpleName());
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        List<EventHandler<?>> eventHandlers = handlers.get(eventType);
        if (eventHandlers != null) {
            eventHandlers.removeIf(h -> h.handler().equals(handler));
            logger.debug("Unsubscribed from event: {}", eventType.getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void publish(T event) {
        if (shutdown || event == null) {
            return;
        }

        Class<?> eventType = event.getClass();
        List<EventHandler<?>> eventHandlers = handlers.get(eventType);

        if (eventHandlers == null || eventHandlers.isEmpty()) {
            logger.trace("No handlers for event: {}", eventType.getSimpleName());
            return;
        }

        logger.debug("Publishing event: {}", eventType.getSimpleName());

        for (EventHandler<?> eventHandler : eventHandlers) {
            EventHandler<T> typedHandler = (EventHandler<T>) eventHandler;
            if (typedHandler.runOnFxThread()) {
                Platform.runLater(() -> typedHandler.handler().accept(event));
            } else {
                try {
                    typedHandler.handler().accept(event);
                } catch (Exception e) {
                    logger.error("Error handling event: {}", eventType.getSimpleName(), e);
                }
            }
        }
    }

    public void shutdown() {
        shutdown = true;
        handlers.clear();
        logger.info("EventBus shutdown");
    }

    public void clearSubscriptions() {
        handlers.clear();
    }

    private record EventHandler<T>(Consumer<T> handler, boolean runOnFxThread) {
    }

    public static final class ConnectionCreatedEvent {
        private final String connectionId;

        public ConnectionCreatedEvent(String connectionId) {
            this.connectionId = connectionId;
        }

        public String getConnectionId() {
            return connectionId;
        }
    }

    public static final class ConnectionClosedEvent {
        private final String connectionId;

        public ConnectionClosedEvent(String connectionId) {
            this.connectionId = connectionId;
        }

        public String getConnectionId() {
            return connectionId;
        }
    }

    public static final class QueryExecutedEvent {
        private final String sql;
        private final long executionTime;
        private final int rowCount;

        public QueryExecutedEvent(String sql, long executionTime, int rowCount) {
            this.sql = sql;
            this.executionTime = executionTime;
            this.rowCount = rowCount;
        }

        public String getSql() {
            return sql;
        }

        public long getExecutionTime() {
            return executionTime;
        }

        public int getRowCount() {
            return rowCount;
        }
    }

    public static final class SchemaChangedEvent {
        private final String connectionId;
        private final String objectName;

        public SchemaChangedEvent(String connectionId, String objectName) {
            this.connectionId = connectionId;
            this.objectName = objectName;
        }

        public String getConnectionId() {
            return connectionId;
        }

        public String getObjectName() {
            return objectName;
        }
    }

    public static final class StatusMessageEvent {
        private final String message;
        private final MessageType type;

        public StatusMessageEvent(String message, MessageType type) {
            this.message = message;
            this.type = type;
        }

        public StatusMessageEvent(String message) {
            this(message, MessageType.INFO);
        }

        public String getMessage() {
            return message;
        }

        public MessageType getType() {
            return type;
        }

        public enum MessageType {
            INFO, SUCCESS, WARNING, ERROR
        }
    }
}
