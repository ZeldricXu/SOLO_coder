package com.datapipeline.config;

import com.datapipeline.common.event.EventPublisher;
import com.datapipeline.common.event.InMemoryEventPublisher;
import com.datapipeline.core.CoreProcessor;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.core.persistence.ResultPersister;
import com.datapipeline.core.resource.PooledResource;
import com.datapipeline.core.resource.ResourcePool;
import com.datapipeline.core.transform.DataTransformer;
import com.datapipeline.core.validation.ParameterValidator;
import com.datapipeline.data.cache.*;
import com.datapipeline.data.repository.ConfigRepository;
import com.datapipeline.data.repository.ResourceRepository;
import com.datapipeline.data.repository.RunInstanceRepository;
import com.datapipeline.dp.budget.PrivacyBudgetManager;
import com.datapipeline.dp.injector.PrivacyInjector;
import com.datapipeline.dp.noise.NoiseGenerator;
import com.datapipeline.fl.aggregation.GradientAggregator;
import com.datapipeline.fl.coordinator.FederatedCoordinator;
import com.datapipeline.fl.crypto.GradientEncryptor;
import com.datapipeline.gateway.logging.RequestLogger;
import com.datapipeline.gateway.tracing.TraceManager;
import com.datapipeline.monitoring.alert.AlertRuleEngine;
import com.datapipeline.monitoring.stats.StatisticsCollector;
import com.datapipeline.notification.delivery.NotificationDispatcher;
import com.datapipeline.notification.queue.NotificationQueue;
import com.datapipeline.notification.suppression.SuppressionStrategy;
import com.datapipeline.scheduler.TaskScheduler;
import com.datapipeline.scheduler.TaskTracker;
import com.datapipeline.tee.attestation.RemoteAttestationService;
import com.datapipeline.tee.auth.SecureAuthService;
import com.datapipeline.tee.enclave.EnclaveManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class AppConfig {

    @Value("${cache.local.maxSize:10000}")
    private long localCacheMaxSize;

    @Value("${cache.local.ttlMinutes:10}")
    private int localCacheTtlMinutes;

    @Value("${resource.pool.maxSize:10}")
    private int resourcePoolMaxSize;

    @Value("${scheduler.threadPoolSize:4}")
    private int schedulerThreadPoolSize;

    @Value("${notification.queueCapacity:1000}")
    private int notificationQueueCapacity;

    @Value("${app.jwt.secret:data-pipeline-secret-key-must-be-long-enough-32-chars}")
    private String jwtSecret;

    @Bean
    public CacheBackend localCacheBackend() {
        return new CaffeineCacheBackend(localCacheMaxSize, Duration.ofMinutes(localCacheTtlMinutes));
    }

    @Bean
    public CacheBackend remoteCacheBackend() {
        return new NoOpCacheBackend();
    }

    @Bean
    public CacheManager cacheManager(CacheBackend localCacheBackend, CacheBackend remoteCacheBackend) {
        return new CacheManager(localCacheBackend, remoteCacheBackend);
    }

    @Bean
    public CacheInvalidationListener cacheInvalidationListener() {
        return new CacheInvalidationListener();
    }

    @Bean
    public ResourceRepository resourceRepository(CacheManager cacheManager) {
        return new ResourceRepository(cacheManager);
    }

    @Bean
    public ConfigRepository configRepository(CacheManager cacheManager) {
        return new ConfigRepository(cacheManager);
    }

    @Bean
    public RunInstanceRepository runInstanceRepository(CacheManager cacheManager) {
        return new RunInstanceRepository(cacheManager);
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new InMemoryEventPublisher();
    }

    @Bean
    public ParameterValidator parameterValidator() {
        return new ParameterValidator();
    }

    @Bean
    public ResourcePool resourcePool() {
        return new ResourcePool(resourcePoolMaxSize, () -> PooledResource.builder()
                .id("res_" + UUID.randomUUID())
                .build());
    }

    @Bean
    public DataTransformer dataTransformer() {
        return new DataTransformer();
    }

    @Bean
    public ResultPersister resultPersister(RunInstanceRepository runInstanceRepository) {
        return new ResultPersister(runInstanceRepository);
    }

    @Bean
    public MetricsRecorder metricsRecorder() {
        return new MetricsRecorder();
    }

    @Bean
    public CoreProcessor coreProcessor(ParameterValidator validator,
                                       ConfigRepository configRepository,
                                       ResourceRepository resourceRepository,
                                       ResourcePool resourcePool,
                                       DataTransformer transformer,
                                       ResultPersister persister,
                                       EventPublisher eventPublisher,
                                       MetricsRecorder metricsRecorder) {
        return new CoreProcessor(validator, configRepository, resourceRepository,
                resourcePool, transformer, persister, eventPublisher, metricsRecorder);
    }

    @Bean
    public RequestLogger requestLogger() {
        return new RequestLogger();
    }

    @Bean
    public TraceManager traceManager() {
        return new TraceManager();
    }

    @Bean
    public AlertRuleEngine alertRuleEngine() {
        return new AlertRuleEngine();
    }

    @Bean
    public StatisticsCollector statisticsCollector() {
        return new StatisticsCollector();
    }

    @Bean
    public SuppressionStrategy suppressionStrategy() {
        return new SuppressionStrategy();
    }

    @Bean
    public NotificationQueue notificationQueue() {
        return new NotificationQueue(notificationQueueCapacity);
    }

    @Bean
    public NotificationDispatcher notificationDispatcher(NotificationQueue queue, SuppressionStrategy strategy) {
        return new NotificationDispatcher(queue, strategy);
    }

    @Bean
    public TaskScheduler taskScheduler() {
        return new TaskScheduler(schedulerThreadPoolSize);
    }

    @Bean
    public TaskTracker taskTracker() {
        return new TaskTracker();
    }

    @Bean
    public NoiseGenerator noiseGenerator() {
        return new NoiseGenerator();
    }

    @Bean
    public PrivacyBudgetManager privacyBudgetManager() {
        return new PrivacyBudgetManager();
    }

    @Bean
    public PrivacyInjector privacyInjector(NoiseGenerator noiseGenerator, PrivacyBudgetManager budgetManager) {
        return new PrivacyInjector(noiseGenerator, budgetManager);
    }

    @Bean
    public GradientAggregator gradientAggregator() {
        return new GradientAggregator();
    }

    @Bean
    public GradientEncryptor gradientEncryptor() {
        return new GradientEncryptor();
    }

    @Bean
    public FederatedCoordinator federatedCoordinator(GradientAggregator aggregator, GradientEncryptor encryptor) {
        return new FederatedCoordinator(aggregator, encryptor);
    }

    @Bean
    public EnclaveManager enclaveManager() {
        return new EnclaveManager();
    }

    @Bean
    public RemoteAttestationService remoteAttestationService() {
        return new RemoteAttestationService();
    }

    @Bean
    public SecureAuthService secureAuthService() {
        return new SecureAuthService(jwtSecret);
    }

}
