package com.enterprise.gateway.routing.matcher;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class RegexRouteMatcher implements RouteMatcher {

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    @Override
    public boolean matches(String pattern, String path) {
        Pattern compiled = patternCache.computeIfAbsent(pattern, Pattern::compile);
        return compiled.matcher(path).matches();
    }
}
