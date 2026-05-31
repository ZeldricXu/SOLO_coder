package com.streamsql.event.listeners;

import com.streamsql.event.DomainEvent;
import com.streamsql.event.EventListener;
import com.streamsql.event.EventBusConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QualityCheckEventListener implements EventListener<Object> {

    @Override
    public void onEvent(DomainEvent<Object> event) {
        log.info("Quality check event received: {} - {}", event.getEventType(), event.getEventId());

        switch (event.getEventType()) {
            case EventBusConfig.EventType.QUALITY_CHECK_COMPLETED -> handleQualityCheckCompleted(event);
            case EventBusConfig.EventType.QUALITY_RULE_CREATED -> handleQualityRuleCreated(event);
            default -> log.debug("Unhandled event type: {}", event.getEventType());
        }
    }

    private void handleQualityCheckCompleted(DomainEvent<Object> event) {
        log.info("Processing quality check completed event: {}", event.getPayload());
    }

    private void handleQualityRuleCreated(DomainEvent<Object> event) {
        log.info("Processing quality rule created event: {}", event.getPayload());
    }

    @Override
    public boolean supports(String eventType) {
        return eventType.startsWith("quality_");
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
