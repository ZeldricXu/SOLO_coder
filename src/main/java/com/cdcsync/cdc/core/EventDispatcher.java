package com.cdcsync.cdc.core;

import com.cdcsync.cdc.domain.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class EventDispatcher {

    private final List<String> kafkaTopics = new ArrayList<>();
    private final List<String> httpEndpoints = new ArrayList<>();

    public void addKafkaTopic(String topic) {
        kafkaTopics.add(topic);
    }

    public void addHttpEndpoint(String endpoint) {
        httpEndpoints.add(endpoint);
    }

    public void dispatch(ChangeEvent event) {
        log.info("Dispatching event: {} from table {}.{}",
                event.getOperationType(), event.getSourceDatabase(), event.getSourceTable());

        for (String topic : kafkaTopics) {
            sendToKafka(topic, event);
        }

        for (String endpoint : httpEndpoints) {
            sendToHttp(endpoint, event);
        }
    }

    private void sendToKafka(String topic, ChangeEvent event) {
        log.debug("Sending event to Kafka topic: {}", topic);
    }

    private void sendToHttp(String endpoint, ChangeEvent event) {
        log.debug("Sending event to HTTP endpoint: {}", endpoint);
    }
}
