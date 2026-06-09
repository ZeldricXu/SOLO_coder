package com.company.dbstudio.test;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import org.junit.jupiter.api.BeforeAll;

import java.util.concurrent.CountDownLatch;

public abstract class BaseTest {

    private static boolean javaFxInitialized = false;

    @BeforeAll
    public static void initJavaFX() throws Exception {
        if (!javaFxInitialized) {
            final CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            latch.await();
            javaFxInitialized = true;
        }
    }

    protected void runOnFxThread(Runnable runnable) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }
}
