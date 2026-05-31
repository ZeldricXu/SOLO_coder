package com.chaoslab.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Hooks;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.util.concurrent.TimeUnit;

public abstract class BaseTest {

    protected AutoCloseable closeable;
    protected VirtualTimeScheduler virtualTimeScheduler;

    @BeforeEach
    protected void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        Hooks.onOperatorDebug();
        virtualTimeScheduler = VirtualTimeScheduler.getOrSet();
    }

    @AfterEach
    protected void tearDown() throws Exception {
        if (closeable != null) {
            closeable.close();
        }
        VirtualTimeScheduler.reset();
        Hooks.resetOnOperatorDebug();
        System.gc();
        TimeUnit.MILLISECONDS.sleep(10);
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
