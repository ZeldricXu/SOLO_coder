package com.solo.config;

import com.solo.config.common.exception.BusinessException;
import com.solo.config.module.sidecar.SidecarProperties;
import com.solo.config.module.sidecar.SidecarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SidecarTimeoutTest {

    @Autowired
    private SidecarService sidecarService;

    @Autowired
    private SidecarProperties properties;

    @Test
    void testTimeoutConfiguration() {
        assertTrue(properties.getTimeout().getInjectTimeoutMs() > 0);
        assertTrue(properties.getTimeout().getUpdateTimeoutMs() > 0);
        assertTrue(properties.getTimeout().getRemoveTimeoutMs() > 0);
        assertTrue(properties.getTimeout().getHeartbeatTimeoutMs() > 0);
        assertTrue(properties.getTimeout().getQueryTimeoutMs() > 0);
    }

    @Test
    void testHeartbeatWithInvalidInstance() {
        StepVerifier.create(sidecarService.heartbeat("non-existent-instance"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void testGetInstanceWithInvalidId() {
        StepVerifier.create(sidecarService.getInstance("non-existent-instance"))
                .expectNextCount(0)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void testTimeoutPropertiesValues() {
        assertEquals(5000, properties.getTimeout().getInjectTimeoutMs());
        assertEquals(3000, properties.getTimeout().getUpdateTimeoutMs());
        assertEquals(3000, properties.getTimeout().getRemoveTimeoutMs());
        assertEquals(2000, properties.getTimeout().getHeartbeatTimeoutMs());
        assertEquals(2000, properties.getTimeout().getQueryTimeoutMs());
    }
}
