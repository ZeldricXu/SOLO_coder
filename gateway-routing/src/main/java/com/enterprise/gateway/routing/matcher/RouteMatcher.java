package com.enterprise.gateway.routing.matcher;

public interface RouteMatcher {

    boolean matches(String pattern, String path);
}
