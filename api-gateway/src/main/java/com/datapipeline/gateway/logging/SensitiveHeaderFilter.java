package com.datapipeline.gateway.logging;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

public class SensitiveHeaderFilter {

    private static final List<String> DEFAULT_SENSITIVE_KEYWORDS = List.of(
            "authorization",
            "token",
            "secret",
            "password",
            "passwd",
            "pwd",
            "api-key",
            "apikey",
            "access-key",
            "secret-key",
            "credential",
            "private-key",
            "session-id",
            "sessionid",
            "cookie",
            "x-auth",
            "x-api",
            "set-cookie"
    );

    public static final String MASK_VALUE = "***";

    private final Set<String> sensitiveKeywords;

    public SensitiveHeaderFilter() {
        this(DEFAULT_SENSITIVE_KEYWORDS);
    }

    public SensitiveHeaderFilter(Collection<String> keywords) {
        this.sensitiveKeywords = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String keyword : keywords) {
            this.sensitiveKeywords.add(keyword.toLowerCase());
        }
    }

    public Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>(headers.size());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            result.put(key, isSensitive(key) ? MASK_VALUE : entry.getValue());
        }
        return result;
    }

    public boolean isSensitive(String headerName) {
        if (headerName == null || headerName.isEmpty()) {
            return false;
        }
        String lowerKey = headerName.toLowerCase();
        for (String keyword : sensitiveKeywords) {
            if (lowerKey.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public void addSensitiveKeyword(String keyword) {
        if (keyword != null) {
            sensitiveKeywords.add(keyword.toLowerCase());
        }
    }

    public void removeSensitiveKeyword(String keyword) {
        if (keyword != null) {
            sensitiveKeywords.remove(keyword.toLowerCase());
        }
    }

    public Set<String> getSensitiveKeywords() {
        return Collections.unmodifiableSet(sensitiveKeywords);
    }

}
