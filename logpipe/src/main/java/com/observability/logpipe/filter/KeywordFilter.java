package com.observability.logpipe.filter;

import cn.hutool.core.util.StrUtil;
import com.observability.logpipe.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KeywordFilter implements LogFilter {

    @Override
    public String getType() {
        return "keyword";
    }

    @Override
    public boolean accept(LogEntry entry, Map<String, Object> config) {
        @SuppressWarnings("unchecked")
        List<String> includeKeywords = (List<String>) config.get("include");
        @SuppressWarnings("unchecked")
        List<String> excludeKeywords = (List<String>) config.get("exclude");

        String message = entry.getMessage() != null ? entry.getMessage() : "";

        if (includeKeywords != null && !includeKeywords.isEmpty()) {
            boolean matches = includeKeywords.stream()
                    .anyMatch(keyword -> StrUtil.containsIgnoreCase(message, keyword));
            if (!matches) {
                return false;
            }
        }

        if (excludeKeywords != null && !excludeKeywords.isEmpty()) {
            boolean matches = excludeKeywords.stream()
                    .anyMatch(keyword -> StrUtil.containsIgnoreCase(message, keyword));
            if (matches) {
                return false;
            }
        }

        return true;
    }
}
