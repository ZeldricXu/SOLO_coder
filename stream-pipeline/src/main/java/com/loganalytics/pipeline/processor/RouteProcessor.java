package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.pipeline.route.LogRouter;

import java.util.HashMap;
import java.util.Map;

public class RouteProcessor implements Processor {
    private LogRouter router;

    public RouteProcessor() {
        this.router = new LogRouter(new HashMap<>(), "dead-letter-logs");
    }

    public RouteProcessor(LogRouter router) {
        this.router = router;
    }

    @Override
    public String getType() {
        return "route";
    }

    @Override
    public LogEvent process(LogEvent event) {
        router.route(event);
        return event;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure(Map<String, String> params) {
        Map<String, String> levelToTopicMap = new HashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("cmdb.") && !key.startsWith("cache.") && !key.startsWith("grok.") && !key.equals("excludedLevels") && !key.equals("excludeHealthChecks")) {
                levelToTopicMap.put(key, entry.getValue());
            }
        }
        router = new LogRouter(levelToTopicMap, "dead-letter-logs");
    }

    @Override
    public void close() {
    }

    public LogRouter getRouter() {
        return router;
    }
}
