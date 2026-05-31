package com.metricplatform.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Order(0)
public class GatewayEventHistoryListener {

    private final GatewayEventHistory eventHistory;

    @EventListener
    public void onGatewayEvent(GatewayEvent event) {
        eventHistory.addEvent(event);
    }
}
