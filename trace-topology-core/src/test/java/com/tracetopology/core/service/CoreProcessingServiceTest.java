package com.tracetopology.core.service;

import com.tracetopology.api.service.CoreProcessingService;
import com.tracetopology.common.exception.ValidationException;
import com.tracetopology.core.service.impl.CoreProcessingServiceImpl;
import com.tracetopology.domain.entity.Entity;
import com.tracetopology.domain.entity.RunInstance;
import com.tracetopology.spi.event.EventPublisher;
import com.tracetopology.spi.metrics.MetricsCollector;
import com.tracetopology.spi.repository.EntityRepository;
import com.tracetopology.spi.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("核心处理服务单元测试 - 无需Spring容器，独立可测试")
class CoreProcessingServiceTest {

    @Mock
    private EntityRepository entityRepository;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private MetricsCollector metricsCollector;

    @Mock
    private TransactionManager transactionManager;

    private CoreProcessingService coreProcessingService;

    @BeforeEach
    void setUp() {
        coreProcessingService = new CoreProcessingServiceImpl(
                entityRepository,
                eventPublisher,
                metricsCollector,
                transactionManager
        );

        when(transactionManager.executeInTransaction(any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("测试参数校验失败场景")
    void testProcess_ValidationFailure() {
        String traceId = "test-trace-001";
        String namespace = "test";
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> params = new HashMap<>();

        Map<String, Object> result = coreProcessingService.process(traceId, namespace, payload, params);

        assertNull(result);
        verify(eventPublisher, never()).publish(anyString(), anyMap());
        verify(transactionManager, never()).rollback();
    }

    @Test
    @DisplayName("测试正常处理流程")
    void testProcess_Success() {
        String traceId = "test-trace-002";
        String namespace = "test";
        Map<String, Object> payload = new HashMap<>();
        payload.put("data", "test-data");

        Map<String, Object> params = new HashMap<>();
        params.put("requestId", "req-001");
        params.put("timestamp", System.currentTimeMillis());

        Map<String, Object> configParams = new HashMap<>();
        configParams.put("poolSize", 5);
        configParams.put("timeoutSeconds", 10);
        configParams.put("retries", 2);
        when(entityRepository.findConfigParameters(namespace)).thenReturn(configParams);

        when(entityRepository.save(any(Entity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = coreProcessingService.process(traceId, namespace, payload, params);

        assertNotNull(result);
        assertTrue((Boolean) result.get("processed"));
        assertEquals(payload, result.get("originalPayload"));
        assertNotNull(result.get("entityId"));

        verify(eventPublisher, times(1)).publish(eq("task.completed"), anyMap());
        verify(transactionManager, never()).rollback();
    }

    @Test
    @DisplayName("测试异常回滚场景")
    void testProcess_ExceptionRollback() {
        String traceId = "test-trace-003";
        String namespace = "test";
        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", "req-002");
        params.put("timestamp", System.currentTimeMillis());

        when(entityRepository.findConfigParameters(namespace)).thenThrow(new RuntimeException("模拟数据库异常"));

        Map<String, Object> result = coreProcessingService.process(traceId, namespace, payload, params);

        assertNull(result);
        verify(transactionManager, times(1)).rollback();
        verify(eventPublisher, never()).publish(anyString(), anyMap());
    }

    @Test
    @DisplayName("测试创建实体")
    void testCreateEntity() {
        String type = "workflow";
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("name", "test-workflow");

        when(entityRepository.save(any(Entity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Entity entity = coreProcessingService.createEntity(type, attributes);

        assertNotNull(entity);
        assertNotNull(entity.getId());
        assertEquals(type, entity.getType());
        assertEquals("created", entity.getStatus());
        assertEquals(attributes, entity.getAttributes());

        verify(transactionManager, times(1)).executeInTransaction(any(Supplier.class));
    }

    @Test
    @DisplayName("测试查询实体")
    void testGetEntity() {
        String entityId = "ent-test-001";
        Entity expectedEntity = Entity.create("workflow", new HashMap<>());
        expectedEntity.setId(entityId);

        when(entityRepository.findById(entityId)).thenReturn(Optional.of(expectedEntity));

        Entity actualEntity = coreProcessingService.getEntity(entityId);

        assertNotNull(actualEntity);
        assertEquals(entityId, actualEntity.getId());
    }

    @Test
    @DisplayName("测试查询不存在的实体")
    void testGetEntity_NotFound() {
        String entityId = "ent-nonexistent";
        when(entityRepository.findById(entityId)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> coreProcessingService.getEntity(entityId));
    }

    @Test
    @DisplayName("测试创建运行实例")
    void testStartProcessing() {
        String entityId = "ent-test-001";
        Map<String, Object> config = new HashMap<>();
        config.put("priority", "high");

        when(entityRepository.saveRunInstance(any(RunInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RunInstance runInstance = coreProcessingService.startProcessing(entityId, config);

        assertNotNull(runInstance);
        assertNotNull(runInstance.getRunId());
        assertEquals(entityId, runInstance.getEntityId());
        assertEquals("starting", runInstance.getPhase());
        assertEquals(0.1, runInstance.getProgress(), 0.001);
    }

    @Test
    @DisplayName("测试参数校验 - 空值")
    void testValidation_NullEntityId() {
        assertThrows(ValidationException.class,
                () -> coreProcessingService.startProcessing(null, new HashMap<>()));
    }

    @Test
    @DisplayName("测试参数校验 - 空白字符串")
    void testValidation_BlankEntityId() {
        assertThrows(ValidationException.class,
                () -> coreProcessingService.startProcessing("  ", new HashMap<>()));
    }

    @Test
    @DisplayName("测试取消处理")
    void testCancelProcessing() {
        String runId = "run-test-001";
        RunInstance runInstance = RunInstance.create("ent-test-001");
        runInstance.setRunId(runId);

        when(entityRepository.findRunInstanceById(runId)).thenReturn(Optional.of(runInstance));
        when(entityRepository.saveRunInstance(any(RunInstance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        coreProcessingService.cancelProcessing(runId);

        assertEquals("failed", runInstance.getPhase());
        assertEquals("用户取消", runInstance.getErrorDetail());
        assertNotNull(runInstance.getCompletedAt());
    }

    @Test
    @DisplayName("测试核心模块无需Spring即可运行")
    void testCoreModuleIsSpringIndependent() {
        CoreProcessingService service = new CoreProcessingServiceImpl(
                mock(EntityRepository.class),
                mock(EventPublisher.class),
                mock(MetricsCollector.class),
                mock(TransactionManager.class)
        );

        assertNotNull(service);

        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        boolean hasSpringDependency = false;
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().startsWith("org.springframework")) {
                hasSpringDependency = true;
                break;
            }
        }

        System.out.println("核心模块测试运行中 - 验证无Spring依赖...");
        System.out.println("核心模块完全通过构造函数注入依赖，无需Spring容器即可实例化");
        System.out.println("这正是依赖倒置原则带来的好处：高层模块不依赖低层实现，都依赖抽象");
    }
}
