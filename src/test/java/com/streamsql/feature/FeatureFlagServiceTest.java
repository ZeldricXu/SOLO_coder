package com.streamsql.feature;

import com.streamsql.config.FeatureFlagsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Feature Flag 功能测试")
class FeatureFlagServiceTest {

    @Mock
    private FeatureFlagsConfig featureFlagsConfig;

    @InjectMocks
    private FeatureFlagService featureFlagService;

    @Nested
    @DisplayName("Feature Flag 状态检查")
    class FeatureStatusCheckTest {

        @Test
        @DisplayName("检查流式处理功能 - 启用")
        void shouldReturnTrueWhenStreamingProcessingEnabled() {
            FeatureFlagsConfig.FeatureFlag streamingFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(streamingFlag);

            assertTrue(featureFlagService.isEnabled("streaming-processing"));
        }

        @Test
        @DisplayName("检查流式处理功能 - 禁用")
        void shouldReturnFalseWhenStreamingProcessingDisabled() {
            FeatureFlagsConfig.FeatureFlag streamingFlag = new FeatureFlagsConfig.FeatureFlag(false, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(streamingFlag);

            assertFalse(featureFlagService.isEnabled("streaming-processing"));
        }

        @Test
        @DisplayName("检查事件驱动架构功能")
        void shouldCheckEventDrivenArchitecture() {
            FeatureFlagsConfig.FeatureFlag eventFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getEventDrivenArchitecture()).thenReturn(eventFlag);

            assertTrue(featureFlagService.isEnabled("event-driven-architecture"));
        }

        @Test
        @DisplayName("检查熔断器功能")
        void shouldCheckCircuitBreaker() {
            FeatureFlagsConfig.FeatureFlag cbFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getCircuitBreaker()).thenReturn(cbFlag);

            assertTrue(featureFlagService.isEnabled("circuit-breaker"));
        }

        @Test
        @DisplayName("检查重试策略功能")
        void shouldCheckRetryPolicy() {
            FeatureFlagsConfig.FeatureFlag retryFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getRetryPolicy()).thenReturn(retryFlag);

            assertTrue(featureFlagService.isEnabled("retry-policy"));
        }

        @Test
        @DisplayName("检查降级策略功能")
        void shouldCheckFallbackPolicy() {
            FeatureFlagsConfig.FeatureFlag fallbackFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getFallbackPolicy()).thenReturn(fallbackFlag);

            assertTrue(featureFlagService.isEnabled("fallback-policy"));
        }

        @Test
        @DisplayName("未知功能名称返回false")
        void shouldReturnFalseForUnknownFeature() {
            assertFalse(featureFlagService.isEnabled("unknown-feature"));
        }
    }

    @Nested
    @DisplayName("带Feature Flag的执行测试")
    class ExecutionWithFeatureFlagTest {

        @Test
        @DisplayName("功能启用时执行主逻辑")
        void shouldExecuteMainLogicWhenFeatureEnabled() {
            FeatureFlagsConfig.FeatureFlag featureFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(featureFlag);

            String result = featureFlagService.executeWithFeature(
                    "streaming-processing",
                    () -> "main result",
                    () -> "fallback result"
            );

            assertEquals("main result", result);
        }

        @Test
        @DisplayName("功能禁用时执行降级逻辑")
        void shouldExecuteFallbackWhenFeatureDisabled() {
            FeatureFlagsConfig.FeatureFlag featureFlag = new FeatureFlagsConfig.FeatureFlag(false, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(featureFlag);

            String result = featureFlagService.executeWithFeature(
                    "streaming-processing",
                    () -> "main result",
                    () -> "fallback result"
            );

            assertEquals("fallback result", result);
        }

        @Test
        @DisplayName("主逻辑异常时执行降级逻辑")
        void shouldExecuteFallbackWhenMainLogicFails() {
            FeatureFlagsConfig.FeatureFlag featureFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(featureFlag);

            String result = featureFlagService.executeWithFeature(
                    "streaming-processing",
                    () -> {
                        throw new RuntimeException("Main logic failed");
                    },
                    () -> "fallback result"
            );

            assertEquals("fallback result", result);
        }

        @Test
        @DisplayName("功能启用时执行Runnable主逻辑")
        void shouldExecuteRunnableWhenFeatureEnabled() {
            FeatureFlagsConfig.FeatureFlag featureFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(featureFlag);

            var flag = new boolean[1];
            featureFlagService.executeWithFeature(
                    "streaming-processing",
                    () -> flag[0] = true,
                    () -> flag[0] = false
            );

            assertTrue(flag[0]);
        }

        @Test
        @DisplayName("功能禁用时执行Runnable降级逻辑")
        void shouldExecuteRunnableFallbackWhenFeatureDisabled() {
            FeatureFlagsConfig.FeatureFlag featureFlag = new FeatureFlagsConfig.FeatureFlag(false, "测试");
            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(featureFlag);

            var flag = new boolean[1];
            featureFlagService.executeWithFeature(
                    "streaming-processing",
                    () -> flag[0] = true,
                    () -> flag[0] = false
            );

            assertFalse(flag[0]);
        }
    }

    @Nested
    @DisplayName("获取所有Feature状态测试")
    class GetAllFeatureStatusTest {

        @Test
        @DisplayName("获取所有功能状态")
        void shouldGetAllFeatureStatus() {
            FeatureFlagsConfig.FeatureFlag streamingFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            FeatureFlagsConfig.FeatureFlag eventFlag = new FeatureFlagsConfig.FeatureFlag(false, "测试");
            FeatureFlagsConfig.FeatureFlag cbFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");
            FeatureFlagsConfig.FeatureFlag retryFlag = new FeatureFlagsConfig.FeatureFlag(false, "测试");
            FeatureFlagsConfig.FeatureFlag fallbackFlag = new FeatureFlagsConfig.FeatureFlag(true, "测试");

            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(streamingFlag);
            when(featureFlagsConfig.getEventDrivenArchitecture()).thenReturn(eventFlag);
            when(featureFlagsConfig.getCircuitBreaker()).thenReturn(cbFlag);
            when(featureFlagsConfig.getRetryPolicy()).thenReturn(retryFlag);
            when(featureFlagsConfig.getFallbackPolicy()).thenReturn(fallbackFlag);

            var status = featureFlagService.getAllFeatureStatus();

            assertNotNull(status);
            assertTrue((Boolean) status.get("streamingProcessing"));
            assertFalse((Boolean) status.get("eventDrivenArchitecture"));
            assertTrue((Boolean) status.get("circuitBreaker"));
            assertFalse((Boolean) status.get("retryPolicy"));
            assertTrue((Boolean) status.get("fallbackPolicy"));
        }

        @Test
        @DisplayName("获取所有功能描述")
        void shouldGetAllFeatureDescriptions() {
            FeatureFlagsConfig.FeatureFlag streamingFlag = new FeatureFlagsConfig.FeatureFlag(true, "流式处理");
            FeatureFlagsConfig.FeatureFlag eventFlag = new FeatureFlagsConfig.FeatureFlag(false, "事件驱动");
            FeatureFlagsConfig.FeatureFlag cbFlag = new FeatureFlagsConfig.FeatureFlag(true, "熔断器");
            FeatureFlagsConfig.FeatureFlag retryFlag = new FeatureFlagsConfig.FeatureFlag(false, "重试");
            FeatureFlagsConfig.FeatureFlag fallbackFlag = new FeatureFlagsConfig.FeatureFlag(true, "降级");

            when(featureFlagsConfig.getStreamingProcessing()).thenReturn(streamingFlag);
            when(featureFlagsConfig.getEventDrivenArchitecture()).thenReturn(eventFlag);
            when(featureFlagsConfig.getCircuitBreaker()).thenReturn(cbFlag);
            when(featureFlagsConfig.getRetryPolicy()).thenReturn(retryFlag);
            when(featureFlagsConfig.getFallbackPolicy()).thenReturn(fallbackFlag);

            var descriptions = featureFlagService.getAllFeatureDescriptions();

            assertNotNull(descriptions);
            assertEquals("流式处理", descriptions.get("streamingProcessing"));
            assertEquals("事件驱动", descriptions.get("eventDrivenArchitecture"));
            assertEquals("熔断器", descriptions.get("circuitBreaker"));
            assertEquals("重试", descriptions.get("retryPolicy"));
            assertEquals("降级", descriptions.get("fallbackPolicy"));
        }
    }
}
