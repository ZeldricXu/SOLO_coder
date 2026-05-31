package com.tsdbproxy.cdc.output;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.cdc.dto.CdcEventData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class EventOutputAdapter {

    public void output(CdcEventData event, String outputType, Map<String, Object> config) {
        String eventJson = JSONUtil.toJsonStr(event);

        switch (outputType.toLowerCase()) {
            case "kafka" -> sendToKafka(eventJson, config);
            case "elasticsearch" -> sendToElasticsearch(eventJson, config);
            case "console" -> log.info("CDC输出: {}", eventJson);
            default -> log.warn("不支持的输出类型: {}", outputType);
        }
    }

    private void sendToKafka(String event, Map<String, Object> config) {
        String topic = (String) config.getOrDefault("topic", "cdc_events");
        log.info("发送到Kafka: topic={}, event={}", topic, event);
    }

    private void sendToElasticsearch(String event, Map<String, Object> config) {
        String index = (String) config.getOrDefault("index", "cdc_events");
        log.info("发送到Elasticsearch: index={}, event={}", index, event);
    }
}
