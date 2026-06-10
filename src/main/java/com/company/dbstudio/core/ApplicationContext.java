package com.company.dbstudio.core;

import com.company.dbstudio.connection.ConnectionManager;
import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.ui.DialogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ApplicationContext {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationContext.class);
    private static final ConcurrentHashMap<Class<?>, Object> beanRegistry = new ConcurrentHashMap<>();
    private static ExecutorService executorService;
    private static volatile boolean initialized = false;

    private ApplicationContext() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        logger.info("Initializing application context...");

        executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                Thread.ofVirtual().name("dbstudio-worker-", 0).factory()
        );

        registerBean(EventBus.getInstance());
        registerBean(DataSourceRegistry.getInstance());
        registerBean(new ConnectionManager());
        registerBean(new DialogManager());

        initialized = true;
        logger.info("Application context initialized successfully");
    }

    public static <T> void registerBean(T bean) {
        if (bean == null) {
            throw new IllegalArgumentException("Bean cannot be null");
        }
        beanRegistry.put(bean.getClass(), bean);
        logger.debug("Registered bean: {}", bean.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    public static <T> T getBean(Class<T> beanClass) {
        T bean = (T) beanRegistry.get(beanClass);
        if (bean == null) {
            throw new IllegalStateException("Bean not found: " + beanClass.getName());
        }
        return bean;
    }

    public static ExecutorService getExecutorService() {
        return executorService;
    }

    public static void executeAsync(Runnable task) {
        if (executorService == null || executorService.isShutdown()) {
            throw new IllegalStateException("Executor service is not available");
        }
        executorService.submit(task);
    }

    public static synchronized void shutdown() {
        if (!initialized) {
            return;
        }

        logger.info("Shutting down application context...");

        beanRegistry.values().forEach(bean -> {
            if (bean instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    logger.error("Error closing bean: {}", bean.getClass().getSimpleName(), e);
                }
            }
        });

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        beanRegistry.clear();
        initialized = false;
        logger.info("Application context shutdown complete");
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static ScheduledExecutorService createVirtualThreadScheduledExecutor(String namePattern, int corePoolSize) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                corePoolSize,
                Thread.ofVirtual().name(namePattern).factory()
        );
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
