package com.meshcontrol.eventstore.service;

import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.eventstore.dto.EventPublishRequest;
import com.meshcontrol.eventstore.entity.EventLog;
import com.meshcontrol.eventstore.mapper.EventLogMapper;
import com.meshcontrol.eventstore.mapper.SnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventStoreService - 并发安全测试")
class EventStoreServiceConcurrencyTest {

    @Mock
    private EventLogMapper eventLogMapper;

    @Mock
    private SnapshotMapper snapshotMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EventStoreService eventStoreService;

    private EventPublishRequest createValidRequest(String aggregateId) {
        EventPublishRequest request = new EventPublishRequest();
        request.setAggregateId(aggregateId);
        request.setAggregateType("sidecar");
        request.setEventType("UPDATED");
        request.setPayload(Collections.singletonMap("key", "value"));
        request.setSource("test");
        return request;
    }

    @Test
    @DisplayName("publishEvent - 空值校验 - aggregateId为null")
    void publishEvent_NullAggregateId_ShouldThrowException() {
        EventPublishRequest request = createValidRequest("test-123");
        request.setAggregateId(null);

        assertThrows(BusinessException.class, () -> eventStoreService.publishEvent(request));
    }

    @Test
    @DisplayName("publishEvent - 空值校验 - aggregateId为空白")
    void publishEvent_BlankAggregateId_ShouldThrowException() {
        EventPublishRequest request = createValidRequest("test-123");
        request.setAggregateId("   ");

        assertThrows(BusinessException.class, () -> eventStoreService.publishEvent(request));
    }

    @Test
    @DisplayName("publishEvent - 空值校验 - aggregateType为null")
    void publishEvent_NullAggregateType_ShouldThrowException() {
        EventPublishRequest request = createValidRequest("test-123");
        request.setAggregateType(null);

        assertThrows(BusinessException.class, () -> eventStoreService.publishEvent(request));
    }

    @Test
    @DisplayName("publishEvent - 长度校验 - aggregateId超长")
    void publishEvent_LongAggregateId_ShouldThrowException() {
        EventPublishRequest request = createValidRequest("a".repeat(129));

        assertThrows(BusinessException.class, () -> eventStoreService.publishEvent(request));
    }

    @Test
    @DisplayName("publishEvent - 并发场景下版本号不重复")
    void publishEvent_ConcurrentPublishes_ShouldHaveUniqueVersions() throws Exception {
        int threadCount = 10;
        String aggregateId = "concurrent-test-123";
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger versionCounter = new AtomicInteger(0);
        ConcurrentHashMap<Integer, Boolean> usedVersions = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<Exception> exceptions = new CopyOnWriteArrayList<>();

        when(eventLogMapper.findMaxVersion(any(), any()))
                .thenAnswer(invocation -> {
                    int current = versionCounter.get();
                    return current == 0 ? null : current;
                });

        when(eventLogMapper.insert
(any(EventLog.class)))
                .thenAnswer(invocation -> {
                    EventLog eventLog = invocation.getArgument(0);
                    int version = eventLog.getVersion();
                    if (usedVersions.putIfAbsent(version, true) != null) {
                        throw new org.springframework.dao.DuplicateKeyException(
                                "Duplicate version: " + version);
                    }
                    versionCounter.set(version);
                    return 1;
                });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    EventPublishRequest request = createValidRequest(aggregateId);
                    eventStoreService.publishEvent(request);
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long successCount = usedVersions.size();
        System.out.println("Successful inserts: " + successCount);
        System.out.println("Exceptions: " + exceptions.size());

        assertTrue(successCount > 0, "Should have at least some successful inserts");
        assertEquals(threadCount, successCount + exceptions.stream()
                .filter(e -> e.getMessage().contains("version conflict"))
                .count(), "All threads should either succeed or get version conflict");
    }

    @Test
    @DisplayName("publishEvent - 不同聚合ID并行执行无干扰")
    void publishEvent_DifferentAggregates_ShouldExecuteInParallel() throws Exception {
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CopyOnWriteArrayList<String> results = new CopyOnWriteArrayList<>();

        when(eventLogMapper.findMaxVersion(any(), any())).thenReturn(null);
        when(eventLogMapper.insert(any(EventLog.class))).thenReturn(1);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    EventPublishRequest request = createValidRequest("aggregate-" + index);
                    EventLog result = eventStoreService.publishEvent(request);
                    results.add(result.getAggregateId());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, results.size(), "All requests should complete");
    }

    @Test
    @DisplayName("publishEvent - 输入参数的防御性拷贝 - payload修改不影响内部状态")
    void publishEvent_PayloadModification_ShouldNotAffectStoredEvent() {
        EventPublishRequest request = createValidRequest("test-123");
        Map<String, Object> payload = request.getPayload();
        payload.put("mutable", "original");

        when(eventLogMapper.findMaxVersion(any(), any())).thenReturn(null);
        when(eventLogMapper.insert(any(EventLog.class))).thenAnswer(invocation -> 1);

        eventStoreService.publishEvent(request);

        payload.put("mutable", "modified");

        verify(eventLogMapper).insert(argThat(eventLog ->
                "original".equals(eventLog.getPayload().get("mutable"))
        ));
    }

    @Test
    @DisplayName("createSnapshot - 空值校验")
    void createSnapshot_NullParameters_ShouldThrowException() {
        assertThrows(BusinessException.class,
                () -> eventStoreService.createSnapshot(null, "sidecar"));
        assertThrows(BusinessException.class,
                () -> eventStoreService.createSnapshot("   ", "sidecar"));
        assertThrows(BusinessException.class,
                () -> eventStoreService.createSnapshot("test-123", null));
        assertThrows(BusinessException.class,
                () -> eventStoreService.createSnapshot("test-123", "   "));
    }

    @Test
    @DisplayName("timeTravelQuery - 空值校验")
    void timeTravelQuery_NullParameters_ShouldThrowException() {
        assertThrows(BusinessException.class,
                () -> eventStoreService.timeTravelQuery(null));

        var request = new com.meshcontrol.eventstore.dto.TimetravelQueryRequest();
        assertThrows(BusinessException.class,
                () -> eventStoreService.timeTravelQuery(request));

        request.setAggregateId("test-123");
        assertThrows(BusinessException.class,
                () -> eventStoreService.timeTravelQuery(request));

        request.setAggregateType("sidecar");
        assertThrows(BusinessException.class,
                () -> eventStoreService.timeTravelQuery(request));

        request.setTimestamp(LocalDateTime.now());
        when(snapshotMapper.findByAggregateIdAndTimestampBefore(any(), any(), any()))
                .thenReturn(null);
        when(eventLogMapper.findByAggregateIdAndTimestampBefore(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        assertDoesNotThrow(() -> eventStoreService.timeTravelQuery(request));
    }

    @Test
    @DisplayName("rebuildProjection - 返回不可修改Map，防止外部修改")
    void rebuildProjection_ReturnsUnmodifiableMap_ShouldPreventModification() {
        var request = new com.meshcontrol.eventstore.dto.ProjectionRebuildRequest();
        request.setAggregateId("test-123");
        request.setAggregateType("sidecar");

        when(snapshotMapper.findLatestByAggregateId(any(), any())).thenReturn(null);
        when(eventLogMapper.findByAggregateIdAndVersionGreaterThan(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = eventStoreService.rebuildProjection(request);

        assertThrows(UnsupportedOperationException.class,
                () -> result.put("key", "value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) result.get("state");
        assertThrows(UnsupportedOperationException.class,
                () -> state.put("inner", "value"));
    }
}
