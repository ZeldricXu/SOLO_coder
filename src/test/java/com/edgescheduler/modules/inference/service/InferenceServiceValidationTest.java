package com.edgescheduler.modules.inference.service;

import com.edgescheduler.common.exception.ValidationException;
import com.edgescheduler.modules.inference.domain.AiModel;
import com.edgescheduler.modules.inference.domain.InferenceTask;
import com.edgescheduler.modules.inference.domain.ModelVersionRelease;
import com.edgescheduler.modules.inference.mapper.AiModelMapper;
import com.edgescheduler.modules.inference.mapper.InferenceTaskMapper;
import com.edgescheduler.modules.inference.mapper.ModelVersionReleaseMapper;
import com.edgescheduler.infrastructure.mapper.ConfigMapper;
import com.edgescheduler.domain.entity.ConfigEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InferenceServiceValidationTest {

    @Mock
    private AiModelMapper aiModelMapper;

    @Mock
    private InferenceTaskMapper inferenceTaskMapper;

    @Mock
    private ModelVersionReleaseMapper releaseMapper;

    @Mock
    private ConfigMapper configMapper;

    @Mock
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, Object> valueOps;

    private MeterRegistry meterRegistry;

    @InjectMocks
    private InferenceService inferenceService;

    private static final String TEST_MODEL_ID = "model-001";
    private static final String TEST_DEVICE_ID = "device-001";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        inferenceService = new InferenceService(
                aiModelMapper, inferenceTaskMapper, releaseMapper, configMapper, redisTemplate, meterRegistry
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), any(), anyLong(), any())).thenReturn(Mono.just(true));
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

        ConfigEntity config = new ConfigEntity();
        Map<String, Object> params = new HashMap<>();
        params.put("maxConcurrentTasks", 10);
        config.setParameters(params);
        when(configMapper.selectOne(any())).thenReturn(config);
    }

    // ==================== registerModel 验证测试 ====================

    @Test
    @DisplayName("registerModel - 模型名称空值校验")
    void testRegisterModel_NullModelName() {
        StepVerifier.create(inferenceService.registerModel(null, "1.0.0", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Model name cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 模型名称空字符串校验")
    void testRegisterModel_EmptyModelName() {
        StepVerifier.create(inferenceService.registerModel("   ", "1.0.0", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Model name cannot be empty"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 模型名称超长校验")
    void testRegisterModel_ModelNameTooLong() {
        String longName = "a".repeat(200);
        StepVerifier.create(inferenceService.registerModel(longName, "1.0.0", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 128"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 模型版本空值校验")
    void testRegisterModel_NullVersion() {
        StepVerifier.create(inferenceService.registerModel("MyModel", null, "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Model version cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 模型版本空字符串校验")
    void testRegisterModel_EmptyVersion() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "   ", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Model version cannot be empty"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 模型版本超长校验")
    void testRegisterModel_VersionTooLong() {
        String longVersion = "a".repeat(100);
        StepVerifier.create(inferenceService.registerModel("MyModel", longVersion, "description"))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 32"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 版本格式校验 - 无效格式")
    void testRegisterModel_InvalidVersionFormat() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "invalid", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid version format"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 版本格式校验 - 组件值过大")
    void testRegisterModel_VersionComponentTooLarge() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "1.1000.0", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid version format"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 版本格式校验 - 负数")
    void testRegisterModel_NegativeVersion() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "1.-1.0", "description"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid version format"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 描述超长校验")
    void testRegisterModel_DescriptionTooLong() {
        String longDesc = "a".repeat(2000);
        StepVerifier.create(inferenceService.registerModel("MyModel", "1.0.0", longDesc))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 1024"))
                .verify();
    }

    @Test
    @DisplayName("registerModel - 成功注册")
    void testRegisterModel_Success() {
        when(aiModelMapper.insert(any(AiModel.class))).thenReturn(1);

        StepVerifier.create(inferenceService.registerModel("MyModel", "1.0.0", "Test description"))
                .assertNext(model -> {
                    assertNotNull(model);
                    assertEquals("MyModel", model.getModelName());
                    assertEquals("1.0.0", model.getModelVersion());
                    assertEquals("DRAFT", model.getStatus());
                })
                .verifyComplete();
    }

    // ==================== createNewVersion 验证测试 ====================

    @Test
    @DisplayName("createNewVersion - 父模型ID空值校验")
    void testCreateNewVersion_NullParentId() {
        StepVerifier.create(inferenceService.createNewVersion(null, "2.0.0", "new features"))
                .expectErrorMatches(e -> e.getMessage().contains("Parent model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("createNewVersion - 新版本格式校验")
    void testCreateNewVersion_InvalidVersion() {
        StepVerifier.create(inferenceService.createNewVersion(1L, "invalid", "new features"))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid version format"))
                .verify();
    }

    @Test
    @DisplayName("createNewVersion - 成功创建新版本")
    void testCreateNewVersion_Success() {
        AiModel parentModel = createTestModel(1L, "1.0.0");
        when(aiModelMapper.selectById(1L)).thenReturn(parentModel);
        when(aiModelMapper.insert(any(AiModel.class))).thenReturn(1);

        StepVerifier.create(inferenceService.createNewVersion(1L, "2.0.0", "new features"))
                .assertNext(model -> {
                    assertNotNull(model);
                    assertEquals("2.0.0", model.getModelVersion());
                })
                .verifyComplete();
    }

    // ==================== deployModel 验证测试 ====================

    @Test
    @DisplayName("deployModel - 模型ID空值校验")
    void testDeployModel_NullModelId() {
        StepVerifier.create(inferenceService.deployModel(null, TEST_DEVICE_ID))
                .expectErrorMatches(e -> e.getMessage().contains("Model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("deployModel - 设备ID空值校验")
    void testDeployModel_NullDeviceId() {
        StepVerifier.create(inferenceService.deployModel(1L, null))
                .expectErrorMatches(e -> e.getMessage().contains("Device ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("deployModel - 设备ID超长校验")
    void testDeployModel_DeviceIdTooLong() {
        String longId = "a".repeat(200);
        StepVerifier.create(inferenceService.deployModel(1L, longId))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 128"))
                .verify();
    }

    @Test
    @DisplayName("deployModel - 成功部署")
    void testDeployModel_Success() {
        AiModel model = createTestModel(1L, "1.0.0");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(aiModelMapper.updateById(any(AiModel.class))).thenReturn(1);

        StepVerifier.create(inferenceService.deployModel(1L, TEST_DEVICE_ID))
                .assertNext(result -> assertTrue(result))
                .verifyComplete();
    }

    // ==================== releaseModelVersion 验证测试 ====================

    @Test
    @DisplayName("releaseModelVersion - 模型ID空值校验")
    void testReleaseModelVersion_NullModelId() {
        StepVerifier.create(inferenceService.releaseModelVersion(null, "FULL", "release notes", null))
                .expectErrorMatches(e -> e.getMessage().contains("Model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("releaseModelVersion - 发布类型空值校验")
    void testReleaseModelVersion_NullReleaseType() {
        StepVerifier.create(inferenceService.releaseModelVersion(1L, null, "release notes", null))
                .expectErrorMatches(e -> e.getMessage().contains("Release type cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("releaseModelVersion - 发布类型无效值校验")
    void testReleaseModelVersion_InvalidReleaseType() {
        StepVerifier.create(inferenceService.releaseModelVersion(1L, "INVALID", "release notes", null))
                .expectErrorMatches(e -> e.getMessage().contains("Invalid release type"))
                .verify();
    }

    @Test
    @DisplayName("releaseModelVersion - 发布说明超长校验")
    void testReleaseModelVersion_ReleaseNotesTooLong() {
        String longNotes = "a".repeat(3000);
        StepVerifier.create(inferenceService.releaseModelVersion(1L, "FULL", longNotes, null))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 2048"))
                .verify();
    }

    @Test
    @DisplayName("releaseModelVersion - 灰度设备列表过大校验")
    void testReleaseModelVersion_GrayscaleDevicesTooMany() {
        List<String> tooManyDevices = IntStream.range(0, 1001)
                .mapToObj(i -> "device-" + i)
                .collect(Collectors.toList());

        StepVerifier.create(inferenceService.releaseModelVersion(1L, "GRAYSCALE", "notes", tooManyDevices))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum size of 1000"))
                .verify();
    }

    @Test
    @DisplayName("releaseModelVersion - FULL发布指定灰度设备警告但不失败")
    void testReleaseModelVersion_FullWithGrayscale() {
        AiModel model = createTestModel(1L, "1.0.0");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(releaseMapper.insert(any(ModelVersionRelease.class))).thenReturn(1);

        List<String> devices = Arrays.asList("device1", "device2");
        StepVerifier.create(inferenceService.releaseModelVersion(1L, "FULL", "notes", devices))
                .assertNext(Objects::nonNull)
                .verifyComplete();
    }

    // ==================== rollbackModelVersion 验证测试 ====================

    @Test
    @DisplayName("rollbackModelVersion - 模型ID空值校验")
    void testRollbackModelVersion_NullModelId() {
        StepVerifier.create(inferenceService.rollbackModelVersion(null, "Rollback reason"))
                .expectErrorMatches(e -> e.getMessage().contains("Model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("rollbackModelVersion - 成功回滚")
    void testRollbackModelVersion_Success() {
        AiModel model = createTestModel(1L, "1.0.0");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(aiModelMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(aiModelMapper.updateById(any(AiModel.class))).thenReturn(1);

        StepVerifier.create(inferenceService.rollbackModelVersion(1L, "Rollback reason"))
                .assertNext(result -> assertTrue(result))
                .verifyComplete();
    }

    // ==================== deprecateModelVersion 验证测试 ====================

    @Test
    @DisplayName("deprecateModelVersion - 模型ID空值校验")
    void testDeprecateModelVersion_NullModelId() {
        StepVerifier.create(inferenceService.deprecateModelVersion(null, "Deprecated"))
                .expectErrorMatches(e -> e.getMessage().contains("Model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("deprecateModelVersion - 原因超长校验")
    void testDeprecateModelVersion_ReasonTooLong() {
        String longReason = "a".repeat(1500);
        StepVerifier.create(inferenceService.deprecateModelVersion(1L, longReason))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum length of 1024"))
                .verify();
    }

    // ==================== submitTask 验证测试 ====================

    @Test
    @DisplayName("submitTask - 模型ID空值校验")
    void testSubmitTask_NullModelId() {
        StepVerifier.create(inferenceService.submitTask(null, TEST_DEVICE_ID, Collections.singletonMap("data", "value"), 5))
                .expectErrorMatches(e -> e.getMessage().contains("Model ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 设备ID空值校验")
    void testSubmitTask_NullDeviceId() {
        StepVerifier.create(inferenceService.submitTask(1L, null, Collections.singletonMap("data", "value"), 5))
                .expectErrorMatches(e -> e.getMessage().contains("Device ID cannot be null"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 输入数据空值校验")
    void testSubmitTask_NullInputData() {
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, null, 5))
                .expectErrorMatches(e -> e.getMessage().contains("Input data cannot be null or empty"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 输入数据空Map校验")
    void testSubmitTask_EmptyInputData() {
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, new HashMap<>(), 5))
                .expectErrorMatches(e -> e.getMessage().contains("Input data cannot be null or empty"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 输入数据过大校验")
    void testSubmitTask_InputDataTooLarge() {
        Map<String, Object> largeData = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            largeData.put("field_" + i, "value_" + i);
        }

        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, largeData, 5))
                .expectErrorMatches(e -> e.getMessage().contains("exceeds maximum size"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 优先级范围校验 - 低于最小值")
    void testSubmitTask_PriorityTooLow() {
        Map<String, Object> input = Collections.singletonMap("data", "value");
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, input, 0))
                .expectErrorMatches(e -> e.getMessage().contains("Priority must be between"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 优先级范围校验 - 高于最大值")
    void testSubmitTask_PriorityTooHigh() {
        Map<String, Object> input = Collections.singletonMap("data", "value");
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, input, 11))
                .expectErrorMatches(e -> e.getMessage().contains("Priority must be between"))
                .verify();
    }

    @Test
    @DisplayName("submitTask - 成功提交")
    void testSubmitTask_Success() {
        AiModel model = createTestModel(1L, "1.0.0");
        model.setStatus("DEPLOYED");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(inferenceTaskMapper.insert(any(InferenceTask.class))).thenReturn(1);

        Map<String, Object> input = Collections.singletonMap("image", "base64data");

        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, input, 5))
                .assertNext(task -> {
                    assertNotNull(task);
                    assertEquals(TEST_DEVICE_ID, task.getDeviceId());
                    assertEquals(5, task.getPriority());
                    assertEquals("PENDING", task.getStatus());
                })
                .verifyComplete();
    }

    // ==================== 边界值测试 ====================

    @Test
    @DisplayName("版本号边界值 - 最大合法版本号")
    void testIsValidVersion_MaxComponents() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "999.999.999.999", "test"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("版本号边界值 - 最小合法版本号")
    void testIsValidVersion_MinComponents() {
        StepVerifier.create(inferenceService.registerModel("MyModel", "0.0.0", "test"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("优先级边界值 - 最小值1")
    void testSubmitTask_PriorityMinBoundary() {
        AiModel model = createTestModel(1L, "1.0.0");
        model.setStatus("DEPLOYED");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(inferenceTaskMapper.insert(any(InferenceTask.class))).thenReturn(1);

        Map<String, Object> input = Collections.singletonMap("data", "value");
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, input, 1))
                .assertNext(Objects::nonNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("优先级边界值 - 最大值10")
    void testSubmitTask_PriorityMaxBoundary() {
        AiModel model = createTestModel(1L, "1.0.0");
        model.setStatus("DEPLOYED");
        when(aiModelMapper.selectById(1L)).thenReturn(model);
        when(inferenceTaskMapper.insert(any(InferenceTask.class))).thenReturn(1);

        Map<String, Object> input = Collections.singletonMap("data", "value");
        StepVerifier.create(inferenceService.submitTask(1L, TEST_DEVICE_ID, input, 10))
                .assertNext(Objects::nonNull)
                .verifyComplete();
    }

    @Test
    @DisplayName("字符串长度边界值 - 模型名称刚好128字符")
    void testRegisterModel_ModelNameMaxLengthBoundary() {
        String name128 = "a".repeat(128);
        StepVerifier.create(inferenceService.registerModel(name128, "1.0.0", "test"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("字符串长度边界值 - 模型名称129字符失败")
    void testRegisterModel_ModelNameOverMaxLength() {
        String name129 = "a".repeat(129);
        StepVerifier.create(inferenceService.registerModel(name129, "1.0.0", "test"))
                .expectError(ValidationException.class)
                .verify();
    }

    private AiModel createTestModel(Long id, String version) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelId(TEST_MODEL_ID);
        model.setModelName("TestModel");
        model.setModelVersion(version);
        model.setStatus("DRAFT");
        model.setDescription("Test model");
        model.setModelSizeBytes(1024L);
        model.setChecksum("abc123");
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(LocalDateTime.now());
        return model;
    }
}
