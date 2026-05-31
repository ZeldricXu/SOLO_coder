package com.taskplatform.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.persistence.entity.Experiment;
import com.taskplatform.persistence.entity.PromptVersion;
import com.taskplatform.persistence.mapper.ExperimentMapper;
import com.taskplatform.persistence.mapper.PromptVersionMapper;
import com.taskplatform.test.builder.ExperimentBuilder;
import com.taskplatform.test.builder.PromptVersionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AB实验服务测试 - 参数校验完备性验证")
class ExperimentServiceTest {

    @Mock
    private ExperimentMapper experimentMapper;
    @Mock
    private PromptVersionMapper versionMapper;

    private ExperimentService experimentService;

    @BeforeEach
    void setUp() {
        experimentService = new ExperimentService(experimentMapper, versionMapper);
    }

    @Nested
    @DisplayName("创建实验测试 - 参数校验")
    class CreateExperimentTests {

        @Test
        @DisplayName("正常创建 - 应成功创建DRAFT状态实验")
        void shouldCreateExperimentSuccessfully() {
            String name = "Button Text Test";
            String description = "测试不同按钮文案的点击率";
            String createdBy = "product_manager";

            when(experimentMapper.insert(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.createExperiment(
                    name, description, createdBy);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo(name);
            assertThat(result.getDescription()).isEqualTo(description);
            assertThat(result.getCreatedBy()).isEqualTo(createdBy);
            assertThat(result.getStatus()).isEqualTo("DRAFT");
            assertThat(result.getExperimentId()).isNotNull().startsWith("exp_");
            verify(experimentMapper, times(1)).insert(any(Experiment.class));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("空实验名称 - 应抛出400异常")
        void shouldRejectEmptyName(String name) {
            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.createExperiment(name, "desc", "user"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("name");
        }

        @Test
        @DisplayName("超长实验名称 - 应抛出400异常")
        void shouldRejectTooLongName() {
            String longName = "a".repeat(201);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.createExperiment(longName, "desc", "user"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("200");
        }

        @Test
        @DisplayName("超长描述 - 应抛出400异常")
        void shouldRejectTooLongDescription() {
            String longDesc = "a".repeat(1001);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.createExperiment("name", longDesc, "user"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("1000");
        }

        @Test
        @DisplayName("空createdBy - 应使用默认值")
        void shouldUseDefaultCreatedBy() {
            when(experimentMapper.insert(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.createExperiment("name", "desc", null);

            assertThat(result).isNotNull();
            assertThat(result.getCreatedBy()).isEqualTo("system");
        }

        @Test
        @DisplayName("null描述 - 应接受")
        void shouldAcceptNullDescription() {
            when(experimentMapper.insert(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.createExperiment("name", null, "user");

            assertThat(result).isNotNull();
            assertThat(result.getDescription()).isNull();
        }
    }

    @Nested
    @DisplayName("添加实验版本测试 - 参数校验")
    class AddVariantTests {

        @Test
        @DisplayName("正常添加 - 应成功关联版本到实验")
        void shouldAddVariantSuccessfully() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withId(1L)
                    .withExperimentId("exp-001")
                    .withStatus("DRAFT")
                    .build();
            PromptVersion version = PromptVersionBuilder.aPromptVersion()
                    .withId(10L)
                    .withPromptKey("greeting")
                    .withVersion("1.0.0")
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);
            when(versionMapper.selectById(10L)).thenReturn(version);
            when(experimentMapper.updateById(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.addVariant(
                    1L, 10L, "button_green", 50);

            assertThat(result).isNotNull();
            verify(experimentMapper, times(1)).updateById(any(Experiment.class));
        }

        @Test
        @DisplayName("实验不存在 - 应抛出404")
        void shouldThrow404WhenExperimentNotFound() {
            when(experimentMapper.selectById(999L)).thenReturn(null);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(999L, 10L, "variant-a", 50),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("EXPERIMENT_NOT_FOUND");
        }

        @Test
        @DisplayName("版本不存在 - 应抛出404")
        void shouldThrow404WhenVersionNotFound() {
            Experiment experiment = ExperimentBuilder.anExperiment().build();
            when(experimentMapper.selectById(1L)).thenReturn(experiment);
            when(versionMapper.selectById(999L)).thenReturn(null);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 999L, "variant-a", 50),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("VERSION_NOT_FOUND");
        }

        @Test
        @DisplayName("非DRAFT状态 - 应抛出400异常")
        void shouldRejectWhenNotInDraft() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .build();
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-a", 50),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("DRAFT");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("空variant名称 - 应抛出400")
        void shouldRejectEmptyVariantName(String variantName) {
            Experiment experiment = ExperimentBuilder.anExperiment().buildDRAFT();
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, variantName, 50),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("variantName");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 101, 150})
        @DisplayName("无效流量比例 - 应抛出400")
        void shouldRejectInvalidTrafficPercentage(int percentage) {
            Experiment experiment = ExperimentBuilder.anExperiment().buildDRAFT();
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-a", percentage),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("1-100");
        }

        @Test
        @DisplayName("流量总和超过100% - 应抛出400")
        void shouldRejectWhenTrafficExceeds100() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .buildDRAFT();
            experiment.setVariants(Map.of(
                    "variant-a", Map.of("trafficPercentage", 80)
            ));
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-b", 30),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("100%");
        }

        @Test
        @DisplayName("重复variant名称 - 应抛出400")
        void shouldRejectDuplicateVariantName() {
            Experiment experiment = ExperimentBuilder.anExperiment().buildDRAFT();
            experiment.setVariants(Map.of(
                    "variant-a", Map.of("versionId", 5L)
            ));
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-a", 20),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("variant-a");
        }

        @Test
        @DisplayName("版本已被使用 - 应抛出400")
        void shouldRejectWhenVersionAlreadyUsed() {
            Experiment experiment = ExperimentBuilder.anExperiment().buildDRAFT();
            experiment.setVariants(Map.of(
                    "variant-a", Map.of("versionId", 10L)
            ));
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-b", 20),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("10");
        }

        @Test
        @DisplayName("超过最大variant数量 - 应抛出400")
        void shouldRejectWhenTooManyVariants() {
            Experiment experiment = ExperimentBuilder.anExperiment().buildDRAFT();
            Map<String, Object> existingVariants = new java.util.HashMap<>();
            IntStream.range(0, 9).forEach(i -> existingVariants.put(
                    "variant-" + i, Map.of("versionId", i)));
            experiment.setVariants(existingVariants);
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.addVariant(1L, 10L, "variant-9", 10),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("10");
        }
    }

    @Nested
    @DisplayName("实验启动测试")
    class StartExperimentTests {

        @Test
        @DisplayName("正常启动 - 应设置为RUNNING状态")
        void shouldStartExperimentSuccessfully() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withId(1L)
                    .withStatus("DRAFT")
                    .withVariants(Map.of(
                            "variant-a", Map.of("trafficPercentage", 50),
                            "variant-b", Map.of("trafficPercentage", 50)
                    ))
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);
            when(experimentMapper.updateById(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.startExperiment(1L);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("RUNNING");
            assertThat(result.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("流量不足100% - 应抛出400")
        void shouldRejectWhenTrafficNot100() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("DRAFT")
                    .withVariants(Map.of(
                            "variant-a", Map.of("trafficPercentage", 50)
                    ))
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.startExperiment(1L),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("100%");
        }

        @Test
        @DisplayName("无variant - 应抛出400")
        void shouldRejectWhenNoVariants() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("DRAFT")
                    .withVariants(Map.of())
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.startExperiment(1L),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("至少2个");
        }

        @Test
        @DisplayName("已在运行 - 应抛出400")
        void shouldRejectWhenAlreadyRunning() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.startExperiment(1L),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("RUNNING");
        }
    }

    @Nested
    @DisplayName("实验停止测试")
    class StopExperimentTests {

        @Test
        @DisplayName("正常停止 - 应设置为STOPPED状态")
        void shouldStopExperimentSuccessfully() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withId(1L)
                    .withStatus("RUNNING")
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);
            when(experimentMapper.updateById(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.stopExperiment(1L, "测试完成");

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("STOPPED");
            assertThat(result.getStoppedAt()).isNotNull();
            assertThat(result.getStopReason()).isEqualTo("测试完成");
        }

        @Test
        @DisplayName("非RUNNING状态 - 应抛出400")
        void shouldRejectWhenNotRunning() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("DRAFT")
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.stopExperiment(1L, "reason"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("RUNNING");
        }

        @Test
        @DisplayName("超长停止原因 - 应抛出400")
        void shouldRejectTooLongStopReason() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .build();
            when(experimentMapper.selectById(1L)).thenReturn(experiment);

            String longReason = "a".repeat(501);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.stopExperiment(1L, longReason),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("500");
        }

        @Test
        @DisplayName("空停止原因 - 应接受")
        void shouldAcceptNullStopReason() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .build();

            when(experimentMapper.selectById(1L)).thenReturn(experiment);
            when(experimentMapper.updateById(any(Experiment.class))).thenReturn(1);

            Experiment result = experimentService.stopExperiment(1L, null);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("STOPPED");
        }
    }

    @Nested
    @DisplayName("流量分配测试")
    class TrafficAllocationTests {

        @Test
        @DisplayName("正常分配 - 应根据userId返回对应variant")
        void shouldAllocateTrafficCorrectly() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .withVariants(Map.of(
                            "variant-a", Map.of("trafficPercentage", 30, "versionId", 1L),
                            "variant-b", Map.of("trafficPercentage", 30, "versionId", 2L),
                            "variant-c", Map.of("trafficPercentage", 40, "versionId", 3L)
                    ))
                    .build();

            when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(experiment);

            String userId = "user-123";
            Map<String, Object> result = experimentService.allocateTraffic("exp-001", userId);

            assertThat(result).isNotNull();
            assertThat(result).containsKeys("variantName", "versionId", "trafficPercentage");
        }

        @Test
        @DisplayName("相同userId - 应返回相同variant（确定性）")
        void shouldReturnSameVariantForSameUser() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .withVariants(Map.of(
                            "variant-a", Map.of("trafficPercentage", 50, "versionId", 1L),
                            "variant-b", Map.of("trafficPercentage", 50, "versionId", 2L)
                    ))
                    .build();

            when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(experiment);

            String userId = "consistent-user";
            Map<String, Object> result1 = experimentService.allocateTraffic("exp-001", userId);
            Map<String, Object> result2 = experimentService.allocateTraffic("exp-001", userId);
            Map<String, Object> result3 = experimentService.allocateTraffic("exp-001", userId);

            assertThat(result1.get("variantName")).isEqualTo(result2.get("variantName"));
            assertThat(result2.get("variantName")).isEqualTo(result3.get("variantName"));
        }

        @Test
        @DisplayName("非RUNNING状态 - 应抛出400")
        void shouldRejectWhenNotRunning() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("DRAFT")
                    .build();
            when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(experiment);

            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.allocateTraffic("exp-001", "user"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("RUNNING");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("空userId - 应抛出400")
        void shouldRejectEmptyUserId(String userId) {
            BusinessException exception = catchThrowableOfType(
                    () -> experimentService.allocateTraffic("exp-001", userId),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).contains("userId");
        }

        @Test
        @DisplayName("流量分配统计 - 应接近预期比例")
        void shouldDistributeTrafficRoughly() {
            Experiment experiment = ExperimentBuilder.anExperiment()
                    .withStatus("RUNNING")
                    .withVariants(Map.of(
                            "variant-a", Map.of("trafficPercentage", 30, "versionId", 1L),
                            "variant-b", Map.of("trafficPercentage", 70, "versionId", 2L)
                    ))
                    .build();

            when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(experiment);

            int totalUsers = 10000;
            Map<String, Long> counts = IntStream.range(0, totalUsers)
                    .mapToObj(i -> experimentService.allocateTraffic("exp-001", "user-" + i))
                    .collect(java.util.stream.Collectors.groupingBy(
                            r -> (String) r.get("variantName"),
                            java.util.stream.Collectors.counting()
                    ));

            long countA = counts.getOrDefault("variant-a", 0L);
            long countB = counts.getOrDefault("variant-b", 0L);

            assertThat(countA + countB).isEqualTo(totalUsers);

            double ratioA = (double) countA / totalUsers;
            double ratioB = (double) countB / totalUsers;

            assertThat(ratioA).isBetween(0.25, 0.35);
            assertThat(ratioB).isBetween(0.65, 0.75);
        }
    }
}
