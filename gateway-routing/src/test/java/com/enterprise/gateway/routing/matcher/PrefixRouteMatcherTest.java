package com.enterprise.gateway.routing.matcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixRouteMatcherTest {

    private PrefixRouteMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new PrefixRouteMatcher();
    }

    @Test
    void shouldMatchExactPath() {
        assertThat(matcher.matches("/api/user", "/api/user")).isTrue();
    }

    @Test
    void shouldMatchPrefixWithWildcard() {
        assertThat(matcher.matches("/api/user/**", "/api/user/123/profile")).isTrue();
    }

    @Test
    void shouldMatchSingleSegmentWildcard() {
        assertThat(matcher.matches("/api/user/*", "/api/user/123")).isTrue();
        assertThat(matcher.matches("/api/user/*", "/api/user/123/profile")).isFalse();
    }

    @Test
    void shouldNotMatchNonPrefixPath() {
        assertThat(matcher.matches("/api/order/**", "/api/user/123")).isFalse();
    }

    @Test
    void shouldMatchRootPath() {
        assertThat(matcher.matches("/", "/")).isTrue();
    }

    @Test
    void shouldMatchMultipleSegments() {
        assertThat(matcher.matches("/api/v1/user/**", "/api/v1/user/list")).isTrue();
    }

    @Test
    void shouldReturnFalseForNullPattern() {
        assertThat(matcher.matches(null, "/api/user")).isFalse();
    }
}
