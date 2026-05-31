package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LogPluginManager {

    private final List<LogPlugin> plugins;

    public StructuredLogEvent applyBeforeLog(StructuredLogEvent event) {
        StructuredLogEvent current = event;
        for (LogPlugin plugin : getSortedPlugins()) {
            if (plugin.isEnabled() && plugin.supports(event.getLevel())) {
                plugin.beforeLog(current);
                current = plugin.transform(current);
            }
        }
        return current;
    }

    public void applyAfterLog(StructuredLogEvent event) {
        for (LogPlugin plugin : getSortedPlugins()) {
            if (plugin.isEnabled() && plugin.supports(event.getLevel())) {
                plugin.afterLog(event);
            }
        }
    }

    private List<LogPlugin> getSortedPlugins() {
        List<LogPlugin> sorted = new ArrayList<>(plugins);
        sorted.sort(Comparator.comparingInt(LogPlugin::getOrder));
        return sorted;
    }
}
