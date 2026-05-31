package com.streamsql.event.listeners;

import com.streamsql.event.DomainEvent;
import com.streamsql.event.EventListener;
import com.streamsql.event.EventBusConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationEventListener implements EventListener<Object> {

    @Override
    public void onEvent(DomainEvent<Object> event) {
        log.info("Notification event received: {} - {}", event.getEventType(), event.getEventId());

        if (shouldTriggerAlert(event)) {
            sendNotification(event);
        }
    }

    private boolean shouldTriggerAlert(DomainEvent<Object> event) {
        return event.getEventType().equals(EventBusConfig.EventType.ALERT_TRIGGERED)
                || event.getEventType().equals(EventBusConfig.EventType.QUALITY_CHECK_COMPLETED);
    }

    private void sendNotification(DomainEvent<Object> event) {
        log.info("Sending notification for event: {}", event.getEventId());
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
