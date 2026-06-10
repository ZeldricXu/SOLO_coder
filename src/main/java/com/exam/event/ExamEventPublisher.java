package com.exam.event;

import com.exam.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExamEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final List<ExamEventListener> listeners = new ArrayList<>();

    @Autowired(required = false)
    public void setListeners(List<ExamEventListener> listenerList) {
        if (listenerList != null) {
            this.listeners.addAll(listenerList);
            this.listeners.sort(Comparator.comparingInt(ExamEventListener::getOrder));
        }
    }

    public void publishEvent(ExamEvent event) {
        log.debug("发布考试事件: type={}, examId={}, sessionId={}",
                event.getEventType(), event.getExamId(), event.getSessionId());

        notifyLocalListeners(event);
        broadcastViaMQ(event);
    }

    private void notifyLocalListeners(ExamEvent event) {
        for (ExamEventListener listener : listeners) {
            try {
                if (listener.supports(event.getEventType())) {
                    listener.onEvent(event);
                }
            } catch (Exception e) {
                log.error("事件监听器处理失败: listener={}, eventType={}",
                        listener.getName(), event.getEventType(), e);
            }
        }
    }

    private void broadcastViaMQ(ExamEvent event) {
        try {
            String routingKey = Constants.MQ_ROUTING_KEY_GRADING + ".event." + event.getEventType();
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(Constants.MQ_EXCHANGE_GRADING, routingKey, json);
        } catch (Exception e) {
            log.warn("通过MQ广播事件失败，仅本地处理: eventType={}", event.getEventType(), e);
        }
    }

    public void registerListener(ExamEventListener listener) {
        listeners.add(listener);
        listeners.sort(Comparator.comparingInt(ExamEventListener::getOrder));
    }
}
