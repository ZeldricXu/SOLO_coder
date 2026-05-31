package com.solocoder.infrastructure.adapter.featurestore.event;

import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LoggingFeatureEventListener implements FeatureEventListener {

    private final StructuredLoggerPort logger;

    @Override
    public void onEvent(FeatureEvent event) {
        Map<String, Object> context = new HashMap<>();
        context.put("eventId", event.getEventId());
        context.put("eventType", event.getEventType());
        context.put("featureName", event.getFeatureName());
        if (event.getEntityId() != null) {
            context.put("entityId", event.getEntityId());
        }
        context.put("timestamp", event.getTimestamp().toString());

        switch (event.getEventType()) {
            case FEATURE_REGISTERED:
                logger.info("特征已注册", context);
                break;
            case FEATURE_INGESTED:
                logger.debug("特征已入库", context);
                break;
            case FEATURE_SYNCED:
                logger.info("特征已同步", context);
                break;
            case CONSISTENCY_CHECK_FAILED:
                logger.warn("一致性检查失败", context);
                break;
            case FEATURE_DELETED:
                logger.info("特征已删除", context);
                break;
        }
    }
}
