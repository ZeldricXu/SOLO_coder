package com.enterprise.gateway.routing.matcher;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class PrefixRouteMatcher implements RouteMatcher {

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    @Override
    public boolean matches(String pattern, String path) {
        return antPathMatcher.match(pattern, path);
    }
}
