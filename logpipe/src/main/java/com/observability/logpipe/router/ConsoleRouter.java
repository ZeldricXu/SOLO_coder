package com.observability.logpipe.router;

import com.observability.logpipe.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ConsoleRouter implements LogRouter {

    @Override
    public String getType() {
        return "console";
    }

    @Override
    public void route(LogEntry entry, Map<String, Object> config) {
        String format = (String) config.getOrDefault("format", "[{}] {}: {}");
        System.out.printf(format + "%n",
                entry.getTimestamp(),
                entry.getLevel(),
                entry.getMessage());
    }
}
