package com.enterprise.gateway.routing.matcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegexRouteMatcherTest {

    private RegexRouteMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new RegexRouteMatcher();
    }

    @Test
    void shouldMatchExactRegex() {
        assertThat(matcher.matches("/api/user/\\d+", "/api/user/123")).isTrue();
    }

    @Test
    void shouldMatchComplexRegex() {
        assertThat(matcher.matches("/api/v[0-9]+/user/\\w+", "/api/v2/user/john")).isTrue();
    }

    @Test
    void shouldNotMatchNonMatchingPath() {
        assertThat(matcher.matches("/api/order/\\d+", "/api/user/123")).isFalse();
    }

    @Test
    void shouldHandleNullPattern() {
        assertThat(matcher.matches(null, "/api/user/123")).isFalse();
    }

    @Test
    void shouldCacheCompiledPatterns() {
        String pattern = "/api/user/\\d+";

        boolean firstResult = matcher.matches(pattern, "/api/user/123");
        boolean secondResult = matcher.matches(pattern, "/api/user/456");

        assertThat(firstResult).isTrue();
        assertThat(secondResult).isTrue();
    }
}
