package com.logmanager.service.pipeline.filter;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.pipeline.LogFilter;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class KeywordFilter implements LogFilter {
    private final Set<String> keywords;
    private final boolean requireMatch;

    public static KeywordFilter contain(Set<String> requiredKeywords) {
        return new KeywordFilter(requiredKeywords, true);
    }

    public static KeywordFilter exclude(Set<String> excludedKeywords) {
        return new KeywordFilter(excludedKeywords, false);
    }

    @Override
    public boolean accept(LogEntry logEntry) {
        String message = logEntry.getMessage();
        if (message == null) {
            return !requireMatch;
        }
        boolean hasMatch = keywords.stream().anyMatch(message::contains);
        return requireMatch ? hasMatch : !hasMatch;
    }
}
