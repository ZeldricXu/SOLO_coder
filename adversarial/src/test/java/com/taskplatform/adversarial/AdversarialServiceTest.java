package com.taskplatform.adversarial;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.adversarial.strategy.JailbreakStrategy;
import com.taskplatform.adversarial.strategy.PromptInjectionStrategy;
import com.taskplatform.adversarial.strategy.TokenSmugglingStrategy;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.persistence.entity.AdversarialSample;
import com.taskplatform.persistence.mapper.AdversarialSampleMapper;
import com.taskplatform.test.builder.AdversarialSampleBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("对抗样本服务测试 - 事务回滚正确性验证")
class AdversarialServiceTest {

    @Mock
    private AdversarialSampleMapper sampleMapper;
    @Mock
    private PromptInjectionStrategy injectionStrategy;
    @Mock
    private JailbreakStrategy jailbreakStrategy;
    @Mock
    private TokenSmugglingStrategy smugglingStrategy;

    private AdversarialService adversarialService;

    @BeforeEach
    void setUp() {
        when(injectionStrategy.getName()).thenReturn("prompt_injection");
        when(jailbreakStrategy.getName()).thenReturn("jailbreak");
        when(smugglingStrategy.getName()).thenReturn("token_smuggling");

        adversarialService = new AdversarialService(
                List.of(injectionStrategy, jailbreakStrategy, smugglingStrategy),
                sampleMapper
        );
    }

    @Nested
    @DisplayName("样本生成测试")
    class SampleGenerationTests {

        @Test
        @DisplayName("正常生成 - 应为每种策略创建一个样本")
        void shouldGenerateSamplesForEachStrategy() {
            String originalPrompt = "请介绍一下你自己";
            String targetModel = "test-model-v1";

            when(injectionStrategy.generateAdversarialPrompt(any())).thenReturn("injected prompt");
            when(jailbreakStrategy.generateAdversarialPrompt(any())).thenReturn("jailbreak prompt");
            when(smugglingStrategy.generateAdversarialPrompt(any())).thenReturn("smuggled prompt");
            when(sampleMapper.insert(any(AdversarialSample.class))).thenReturn(1);

            List<AdversarialSample> samples = adversarialService.generateSamples(
                    originalPrompt, targetModel, "test-user");

            assertThat(samples).hasSize(3);
            verify(sampleMapper, times(3)).insert(any(AdversarialSample.class));

            assertThat(samples).extracting(AdversarialSample::getAttackType)
                    .containsExactlyInAnyOrder("prompt_injection", "jailbreak", "token_smuggling");
        }

        @Test
        @DisplayName("策略异常 - 其他策略应继续执行（部分回滚）")
        void shouldContinueWhenOneStrategyFails() {
            String originalPrompt = "测试输入";

            when(injectionStrategy.generateAdversarialPrompt(any())).thenReturn("injected");
            when(jailbreakStrategy.generateAdversarialPrompt(any()))
                    .thenThrow(new RuntimeException("Strategy failed"));
            when(smugglingStrategy.generateAdversarialPrompt(any())).thenReturn("smuggled");
            when(sampleMapper.insert(any(AdversarialSample.class))).thenReturn(1);

            List<AdversarialSample> samples = adversarialService.generateSamples(
                    originalPrompt, "test-model", "test-user");

            assertThat(samples).hasSize(2);
            verify(sampleMapper, times(2)).insert(any(AdversarialSample.class));
        }

        @Test
        @DisplayName("数据库异常 - 已生成的样本不应被回滚")
        void shouldNotRollbackOnDatabaseError() {
            when(injectionStrategy.generateAdversarialPrompt(any())).thenReturn("injected");
            when(jailbreakStrategy.generateAdversarialPrompt(any())).thenReturn("jailbreak");
            when(smugglingStrategy.generateAdversarialPrompt(any())).thenReturn("smuggled");

            when(sampleMapper.insert(any(AdversarialSample.class)))
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenThrow(new RuntimeException("DB connection lost"));

            assertThatCode(() -> adversarialService.generateSamples(
                    "test", "test-model", "test-user"))
                    .doesNotThrowAnyException();

            verify(sampleMapper, times(3)).insert(any(AdversarialSample.class));
        }

        @Test
        @DisplayName("样本属性验证 - 应正确设置所有字段")
        void shouldSetAllSampleFields() {
            String originalPrompt = "原始提示词";
            String targetModel = "my-model";
            String createdBy = "user123";

            when(injectionStrategy.generateAdversarialPrompt(originalPrompt)).thenReturn("adversarial");
            when(sampleMapper.insert(any(AdversarialSample.class))).thenReturn(1);

            adversarialService = new AdversarialService(
                    List.of(injectionStrategy), sampleMapper);

            List<AdversarialSample> samples = adversarialService.generateSamples(
                    originalPrompt, targetModel, createdBy);

            assertThat(samples).hasSize(1);
            AdversarialSample sample = samples.get(0);

            assertThat(sample.getSampleId()).isNotNull().startsWith("sample_");
            assertThat(sample.getOriginalPrompt()).isEqualTo(originalPrompt);
            assertThat(sample.getAdversarialPrompt()).isEqualTo("adversarial");
            assertThat(sample.getTargetModel()).isEqualTo(targetModel);
            assertThat(sample.getCreatedBy()).isEqualTo(createdBy);
            assertThat(sample.getConfidenceScore()).isEqualTo(0.5);
            assertThat(sample.getMetadata()).isNotNull();
            assertThat(sample.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("空目标模型 - 应使用默认值")
        void shouldUseDefaultModelWhenNull() {
            when(injectionStrategy.generateAdversarialPrompt(any())).thenReturn("test");
            when(sampleMapper.insert(any(AdversarialSample.class))).thenReturn(1);

            adversarialService = new AdversarialService(
                    List.of(injectionStrategy), sampleMapper);

            List<AdversarialSample> samples = adversarialService.generateSamples(
                    "test", null, "test-user");

            assertThat(samples).hasSize(1);
            assertThat(samples.get(0).getTargetModel()).isEqualTo("test-model");
        }
    }

    @Nested
    @DisplayName("样本评估测试")
    class SampleEvaluationTests {

        @Test
        @DisplayName("成功评估 - 应更新样本状态和置信度")
        void shouldEvaluateSampleSuccessfully() {
            AdversarialSample sample = AdversarialSampleBuilder.anAdversarialSample()
                    .withSampleId("sample-001")
                    .withAttackType("prompt_injection")
                    .build();

            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sample);
            when(injectionStrategy.evaluateSuccess(anyString(), anyString())).thenReturn(0.75);
            when(sampleMapper.updateById(any(AdversarialSample.class))).thenReturn(1);

            AdversarialService service = new AdversarialService(
                    List.of(injectionStrategy), sampleMapper);

            AdversarialSample result = service.evaluateSample("sample-001", "模型响应内容");

            assertThat(result).isNotNull();
            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getConfidenceScore()).isEqualTo(0.75);
            assertThat(result.getModelResponse()).isEqualTo("模型响应内容");
            assertThat(result.getEvaluationResult()).isNotNull();
            verify(sampleMapper, times(1)).updateById(any(AdversarialSample.class));
        }

        @Test
        @DisplayName("样本不存在 - 应抛出404异常")
        void shouldThrowExceptionWhenSampleNotFound() {
            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException exception = catchThrowableOfType(
                    () -> adversarialService.evaluateSample("nonexistent", "response"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("SAMPLE_NOT_FOUND");
            verify(sampleMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("策略不存在 - 应使用默认评估逻辑")
        void shouldHandleUnknownStrategy() {
            AdversarialSample sample = AdversarialSampleBuilder.anAdversarialSample()
                    .withSampleId("sample-002")
                    .withAttackType("unknown_strategy")
                    .build();

            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sample);
            when(sampleMapper.updateById(any(AdversarialSample.class))).thenReturn(1);

            AdversarialSample result = adversarialService.evaluateSample("sample-002", "response");

            assertThat(result).isNotNull();
            assertThat(result.getConfidenceScore()).isZero();
        }

        @Test
        @DisplayName("评估失败 - 事务应回滚")
        void shouldNotUpdateOnEvaluationError() {
            AdversarialSample sample = AdversarialSampleBuilder.anAdversarialSample()
                    .withSampleId("sample-003")
                    .withAttackType("prompt_injection")
                    .build();

            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sample);
            when(injectionStrategy.evaluateSuccess(anyString(), anyString()))
                    .thenThrow(new RuntimeException("Evaluation failed"));

            assertThatThrownBy(() -> adversarialService.evaluateSample("sample-003", "response"))
                    .isInstanceOf(RuntimeException.class);

            verify(sampleMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("低置信度 - success应设为false")
        void shouldMarkLowConfidenceAsFailure() {
            AdversarialSample sample = AdversarialSampleBuilder.anAdversarialSample()
                    .withSampleId("sample-004")
                    .withAttackType("prompt_injection")
                    .build();

            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sample);
            when(injectionStrategy.evaluateSuccess(anyString(), anyString())).thenReturn(0.3);
            when(sampleMapper.updateById(any(AdversarialSample.class))).thenReturn(1);

            AdversarialService service = new AdversarialService(
                    List.of(injectionStrategy), sampleMapper);
            AdversarialSample result = service.evaluateSample("sample-004", "response");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getConfidenceScore()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("空模型响应 - 应正确处理")
        void shouldHandleNullModelResponse() {
            AdversarialSample sample = AdversarialSampleBuilder.anAdversarialSample()
                    .withSampleId("sample-005")
                    .withAttackType("prompt_injection")
                    .build();

            when(sampleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sample);
            when(injectionStrategy.evaluateSuccess(isNull(), anyString())).thenReturn(0.0);
            when(sampleMapper.updateById(any(AdversarialSample.class))).thenReturn(1);

            AdversarialService service = new AdversarialService(
                    List.of(injectionStrategy), sampleMapper);

            assertThatCode(() -> service.evaluateSample("sample-005", null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("模型安全评估测试")
    class ModelSecurityAssessmentTests {

        @Test
        @DisplayName("正常评估 - 应计算正确的安全分数")
        void shouldCalculateSecurityScore() {
            AdversarialSample success1 = AdversarialSampleBuilder.anAdversarialSample()
                    .buildSuccessfulAttack();
            AdversarialSample success2 = AdversarialSampleBuilder.anAdversarialSample()
                    .buildSuccessfulAttack();
            AdversarialSample failed1 = AdversarialSampleBuilder.anAdversarialSample()
                    .buildFailedAttack();
            AdversarialSample failed2 = AdversarialSampleBuilder.anAdversarialSample()
                    .buildFailedAttack();
            AdversarialSample failed3 = AdversarialSampleBuilder.anAdversarialSample()
                    .buildFailedAttack();

            when(sampleMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(success1, success2, failed1, failed2, failed3));

            Map<String, Object> result = adversarialService.assessModelSecurity("test-model");

            assertThat(result).isNotNull();
            assertThat(result).containsEntry("totalSamples", 5L);
            assertThat(result).containsEntry("successfulAttacks", 2L);
            assertThat(result).containsEntry("attackSuccessRate", 0.4);
            assertThat(result).containsEntry("securityScore", 0.6);
            assertThat(result).containsEntry("riskLevel", "MEDIUM");
        }

        @Test
        @DisplayName("全部成功 - 风险等级应为CRITICAL")
        void shouldReturnCriticalRiskWhenAllAttacksSucceed() {
            List<AdversarialSample> samples = List.of(
                    AdversarialSampleBuilder.anAdversarialSample().buildSuccessfulAttack(),
                    AdversarialSampleBuilder.anAdversarialSample().buildSuccessfulAttack(),
                    AdversarialSampleBuilder.anAdversarialSample().buildSuccessfulAttack()
            );

            when(sampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(samples);

            Map<String, Object> result = adversarialService.assessModelSecurity("test-model");

            assertThat(result).containsEntry("riskLevel", "CRITICAL");
            assertThat(result).containsEntry("securityScore", 0.0);
        }

        @Test
        @DisplayName("全部失败 - 风险等级应为LOW")
        void shouldReturnLowRiskWhenAllAttacksFail() {
            List<AdversarialSample> samples = List.of(
                    AdversarialSampleBuilder.anAdversarialSample().buildFailedAttack(),
                    AdversarialSampleBuilder.anAdversarialSample().buildFailedAttack()
            );

            when(sampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(samples);

            Map<String, Object> result = adversarialService.assessModelSecurity("test-model");

            assertThat(result).containsEntry("riskLevel", "LOW");
            assertThat(result).containsEntry("securityScore", 1.0);
        }

        @Test
        @DisplayName("无样本 - 应抛出异常")
        void shouldThrowExceptionWhenNoSamples() {
            when(sampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            BusinessException exception = catchThrowableOfType(
                    () -> adversarialService.assessModelSecurity("test-model"),
                    BusinessException.class
            );

            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(404);
            assertThat(exception.getErrorCode()).isEqualTo("NO_SAMPLES");
        }

        @Test
        @DisplayName("攻击类型分布 - 应正确统计")
        void shouldCalculateAttackTypeDistribution() {
            AdversarialSample injection = AdversarialSampleBuilder.anAdversarialSample()
                    .withAttackType("prompt_injection")
                    .buildSuccessfulAttack();
            AdversarialSample jailbreak = AdversarialSampleBuilder.anAdversarialSample()
                    .withAttackType("jailbreak")
                    .buildFailedAttack();
            AdversarialSample injection2 = AdversarialSampleBuilder.anAdversarialSample()
                    .withAttackType("prompt_injection")
                    .buildSuccessfulAttack();

            when(sampleMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(injection, jailbreak, injection2));

            Map<String, Object> result = adversarialService.assessModelSecurity("test-model");

            Map<String, Long> distribution = (Map<String, Long>) result.get("attackTypeDistribution");
            assertThat(distribution).containsEntry("prompt_injection", 2L);
            assertThat(distribution).containsEntry("jailbreak", 1L);
        }
    }
}
