package com.healthtrack.service;

import com.healthtrack.entity.HealthAdvice;
import com.healthtrack.entity.HealthGoal;
import com.healthtrack.entity.HealthIndicator;
import com.healthtrack.repository.HealthAdviceRepository;
import com.healthtrack.repository.HealthGoalRepository;
import com.healthtrack.repository.HealthIndicatorRepository;
import com.healthtrack.testbuilder.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("建议推送模块单元测试")
class AdvicePushServiceTest {

    @Mock
    private HealthAdviceRepository healthAdviceRepository;

    @Mock
    private HealthIndicatorRepository healthIndicatorRepository;

    @Mock
    private HealthGoalRepository healthGoalRepository;

    @InjectMocks
    private AdvicePushService advicePushService;

    @BeforeEach
    void setUp() {
        advicePushService.clearDeduplicationCache();
    }

    @Nested
    @DisplayName("去重时间窗口测试")
    class DeduplicationWindowTests {

        @Test
        @DisplayName("高优先级建议去重窗口为1小时")
        void testHighPriorityDedupWindow() {
            long window = advicePushService.getDeduplicationWindowHours("high");
            assertEquals(1, window);
        }

        @Test
        @DisplayName("中优先级建议去重窗口为4小时")
        void testMediumPriorityDedupWindow() {
            long window = advicePushService.getDeduplicationWindowHours("medium");
            assertEquals(4, window);
        }

        @Test
        @DisplayName("低优先级建议去重窗口为24小时")
        void testLowPriorityDedupWindow() {
            long window = advicePushService.getDeduplicationWindowHours("low");
            assertEquals(24, window);
        }

        @Test
        @DisplayName("未知优先级默认使用24小时窗口")
        void testUnknownPriorityDefaultWindow() {
            long window = advicePushService.getDeduplicationWindowHours("unknown");
            assertEquals(24, window);
        }

        @Test
        @DisplayName("不同大小写优先级 - 相同处理")
        void testCaseInsensitivePriority() {
            assertEquals(1, advicePushService.getDeduplicationWindowHours("HIGH"));
            assertEquals(1, advicePushService.getDeduplicationWindowHours("High"));
            assertEquals(1, advicePushService.getDeduplicationWindowHours("high"));
        }
    }

    @Nested
    @DisplayName("相同指标状态去重测试")
    class SameIndicatorDeduplicationTests {

        @Test
        @DisplayName("相同指标状态触发 - 建议合并，不重复生成")
        void testSameIndicatorStatusDeduplication() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator abnormalIndicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(abnormalIndicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>())
                    .thenReturn(List.of(TestDataBuilder.buildHighPriorityAdvice(userId)));
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice firstAdvice = advicePushService.generateAndSaveAdvice(userId, abnormalIndicator);
            assertNotNull(firstAdvice);
            assertEquals("high", firstAdvice.getPriority());
            
            HealthAdvice secondAdvice = advicePushService.generateAndSaveAdvice(userId, abnormalIndicator);
            assertNull(secondAdvice, "相同指标状态应该去重，不生成新建议");
            
            verify(healthAdviceRepository, times(1)).save(any(HealthAdvice.class));
        }

        @Test
        @DisplayName("不同指标状态 - 各自生成建议")
        void testDifferentIndicatorStatusNoDeduplication() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator normalIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            HealthIndicator abnormalIndicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(normalIndicator))
                    .thenReturn(Optional.of(abnormalIndicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice normalAdvice = advicePushService.generateAndSaveAdvice(userId, normalIndicator);
            HealthAdvice abnormalAdvice = advicePushService.generateAndSaveAdvice(userId, abnormalIndicator);
            
            assertNotNull(normalAdvice);
            assertNotNull(abnormalAdvice);
            assertNotEquals(normalAdvice.getPriority(), abnormalAdvice.getPriority());
            
            verify(healthAdviceRepository, times(2)).save(any(HealthAdvice.class));
        }

        @Test
        @DisplayName("相同内容不同用户 - 不触发去重")
        void testSameContentDifferentUsersNoDeduplication() {
            String userId1 = "user_001";
            String userId2 = "user_002";
            HealthIndicator indicator1 = TestDataBuilder.buildAbnormalHeartRateIndicator(userId1);
            HealthIndicator indicator2 = TestDataBuilder.buildAbnormalHeartRateIndicator(userId2);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId1, "heart_rate"))
                    .thenReturn(Optional.of(indicator1));
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId2, "heart_rate"))
                    .thenReturn(Optional.of(indicator2));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(anyString(), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice1 = advicePushService.generateAndSaveAdvice(userId1, indicator1);
            HealthAdvice advice2 = advicePushService.generateAndSaveAdvice(userId2, indicator2);
            
            assertNotNull(advice1);
            assertNotNull(advice2);
            verify(healthAdviceRepository, times(2)).save(any(HealthAdvice.class));
        }
    }

    @Nested
    @DisplayName("去重时间窗口聚合逻辑测试")
    class TimeWindowAggregationTests {

        @Test
        @DisplayName("窗口内相同建议 - 聚合，仅保存一条")
        void testSameAdviceWithinWindowAggregated() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            HealthAdvice existingAdvice = TestDataBuilder.buildHighPriorityAdvice(userId);
            existingAdvice.setGeneratedAt(LocalDateTime.now().minusMinutes(30));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(existingAdvice));

            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertNull(newAdvice, "时间窗口内相同建议应该被聚合");
            verify(healthAdviceRepository, never()).save(any(HealthAdvice.class));
        }

        @Test
        @DisplayName("窗口外相同建议 - 正常生成新建议")
        void testSameAdviceOutsideWindowGenerated() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            HealthAdvice existingAdvice = TestDataBuilder.buildHighPriorityAdvice(userId);
            existingAdvice.setGeneratedAt(LocalDateTime.now().minusHours(2));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertNotNull(newAdvice);
            verify(healthAdviceRepository, times(1)).save(any(HealthAdvice.class));
        }

        @Test
        @DisplayName("高优先级1小时窗口测试")
        void testHighPriorityOneHourWindow() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            HealthAdvice existingAdvice = TestDataBuilder.buildHighPriorityAdvice(userId);
            existingAdvice.setGeneratedAt(LocalDateTime.now().minusMinutes(59));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(existingAdvice));

            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, indicator);
            assertNull(newAdvice, "59分钟内的高优先级建议应该被去重");
        }

        @Test
        @DisplayName("低优先级24小时窗口测试")
        void testLowPriority24HourWindow() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator normalIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            HealthAdvice existingAdvice = TestDataBuilder.buildLowPriorityAdvice(userId);
            existingAdvice.setGeneratedAt(LocalDateTime.now().minusHours(23));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(normalIndicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(existingAdvice));

            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, normalIndicator);
            assertNull(newAdvice, "23小时内的低优先级建议应该被去重");
        }
    }

    @Nested
    @DisplayName("不同紧急程度去重行为差异测试")
    class PriorityDeduplicationBehaviorTests {

        @Test
        @DisplayName("高优先级建议 - 更频繁的生成机会（短窗口）")
        void testHighPriorityMoreFrequentGeneration() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator abnormalIndicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(abnormalIndicator));
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthAdvice firstAdvice = TestDataBuilder.buildHighPriorityAdvice(userId);
            firstAdvice.setGeneratedAt(LocalDateTime.now().minusMinutes(90));
            
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            
            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, abnormalIndicator);
            assertNotNull(newAdvice, "90分钟后的高优先级建议应该重新生成");
        }

        @Test
        @DisplayName("低优先级建议 - 更保守的生成策略（长窗口）")
        void testLowPriorityConservativeGeneration() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator normalIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(normalIndicator));
            
            HealthAdvice existingAdvice = TestDataBuilder.buildLowPriorityAdvice(userId);
            existingAdvice.setGeneratedAt(LocalDateTime.now().minusHours(12));
            
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(existingAdvice));
            
            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, normalIndicator);
            assertNull(newAdvice, "12小时内的低优先级建议不应该重新生成");
        }

        @Test
        @DisplayName("不同优先级相同内容 - 独立去重")
        void testDifferentPrioritiesIndependentDeduplication() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator normalIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            HealthAdvice highPriorityAdvice = TestDataBuilder.buildHighPriorityAdvice(userId);
            highPriorityAdvice.setGeneratedAt(LocalDateTime.now().minusMinutes(30));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(normalIndicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(highPriorityAdvice));
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthAdvice lowPriorityAdvice = advicePushService.generateAndSaveAdvice(userId, normalIndicator);
            assertNotNull(lowPriorityAdvice, "不同优先级的建议应该独立去重");
            assertEquals("low", lowPriorityAdvice.getPriority());
        }

        @Test
        @DisplayName("优先级切换时 - 不受旧优先级影响")
        void testPrioritySwitchNoCrossDeduplication() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator normalIndicator = TestDataBuilder.buildHeartRateIndicator(userId);
            HealthAdvice existingHighPriority = TestDataBuilder.buildHighPriorityAdvice(userId);
            existingHighPriority.setGeneratedAt(LocalDateTime.now().minusMinutes(30));
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(normalIndicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(List.of(existingHighPriority));
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));
            
            HealthAdvice newAdvice = advicePushService.generateAndSaveAdvice(userId, normalIndicator);
            assertNotNull(newAdvice);
            assertEquals("low", newAdvice.getPriority());
        }
    }

    @Nested
    @DisplayName("去重后建议内容完整性测试")
    class AdviceContentIntegrityTests {

        @Test
        @DisplayName("首次生成建议 - 内容完整")
        void testFirstAdviceContentComplete() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertNotNull(advice);
            assertNotNull(advice.getAdviceId());
            assertNotNull(advice.getUserId());
            assertNotNull(advice.getAdviceType());
            assertNotNull(advice.getAdviceContent());
            assertNotNull(advice.getPriority());
            assertNotNull(advice.getBasedIndicators());
            assertEquals("unread", advice.getReadStatus());
            assertFalse(advice.getPushed());
        }

        @Test
        @DisplayName("建议ID格式正确")
        void testAdviceIdFormat() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertTrue(advice.getAdviceId().startsWith("advice_"));
            assertTrue(advice.getAdviceId().length() > 10);
        }

        @Test
        @DisplayName("建议关联指标正确")
        void testAdviceBasedIndicatorCorrect() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertEquals("heart_rate", advice.getBasedIndicators());
        }

        @Test
        @DisplayName("异常状态建议 - 优先级为high")
        void testAbnormalStatusHighPriority() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildAbnormalHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertEquals("high", advice.getPriority());
        }

        @Test
        @DisplayName("正常状态建议 - 优先级为low")
        void testNormalStatusLowPriority() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "heart_rate"))
                    .thenReturn(Optional.of(indicator));
            when(healthGoalRepository.findByUserIdAndGoalType(userId, "heart_rate"))
                    .thenReturn(Optional.empty());
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertEquals("low", advice.getPriority());
        }

        @Test
        @DisplayName("目标进度滞后 - 优先级为medium")
        void testLaggingGoalMediumPriority() {
            String userId = TestDataBuilder.getDefaultUserId();
            HealthIndicator indicator = TestDataBuilder.buildHeartRateIndicator(userId);
            HealthGoal laggingGoal = TestDataBuilder.buildLaggingGoal(userId);
            
            when(healthIndicatorRepository.findByUserIdAndIndicatorType(userId, "weight"))
                    .thenReturn(Optional.of(indicator));
            when(healthGoalRepository.findByUserIdAndGoalType(userId, "weight"))
                    .thenReturn(Optional.of(laggingGoal));
            when(healthAdviceRepository.findByUserIdAndGeneratedAtAfter(eq(userId), any(LocalDateTime.class)))
                    .thenReturn(new ArrayList<>());
            when(healthAdviceRepository.save(any(HealthAdvice.class))).thenAnswer(invocation -> invocation.getArgument(0));

            HealthAdvice advice = advicePushService.generateAndSaveAdvice(userId, indicator);
            
            assertEquals("medium", advice.getPriority());
        }
    }

    @Nested
    @DisplayName("建议聚合功能测试")
    class AdviceAggregationTests {

        @Test
        @DisplayName("同类型多条建议 - 聚合显示数量")
        void testSameTypeAdviceAggregation() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> advices = new ArrayList<>();
            
            for (int i = 0; i < 3; i++) {
                HealthAdvice advice = TestDataBuilder.buildHighPriorityAdvice(userId);
                advice.setGeneratedAt(LocalDateTime.now().minusHours(i));
                advices.add(advice);
            }
            
            when(healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId))
                    .thenReturn(advices);

            List<HealthAdvice> aggregated = advicePushService.aggregateAdvicesByPriority(userId, 10);
            
            assertNotNull(aggregated);
            assertTrue(aggregated.size() <= advices.size());
        }

        @Test
        @DisplayName("不同优先级按顺序返回")
        void testPriorityOrderInAggregation() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> advices = new ArrayList<>();
            advices.add(TestDataBuilder.buildLowPriorityAdvice(userId));
            advices.add(TestDataBuilder.buildHighPriorityAdvice(userId));
            advices.add(TestDataBuilder.buildMediumPriorityAdvice(userId));
            
            when(healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId))
                    .thenReturn(advices);

            List<HealthAdvice> aggregated = advicePushService.aggregateAdvicesByPriority(userId, 10);
            
            assertNotNull(aggregated);
        }

        @Test
        @DisplayName("限制返回数量")
        void testMaxCountLimit() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> advices = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                advices.add(TestDataBuilder.buildHighPriorityAdvice(userId));
            }
            
            when(healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId))
                    .thenReturn(advices);

            List<HealthAdvice> aggregated = advicePushService.aggregateAdvicesByPriority(userId, 5);
            
            assertNotNull(aggregated);
            assertTrue(aggregated.size() <= 5);
        }
    }

    @Nested
    @DisplayName("建议查询功能测试")
    class AdviceQueryTests {

        @Test
        @DisplayName("查询用户建议列表")
        void testGetUserAdvices() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> expectedAdvices = Arrays.asList(
                    TestDataBuilder.buildHighPriorityAdvice(userId),
                    TestDataBuilder.buildLowPriorityAdvice(userId)
            );
            
            when(healthAdviceRepository.findByUserIdOrderByPriorityAscGeneratedAtDesc(userId))
                    .thenReturn(expectedAdvices);

            List<HealthAdvice> actualAdvices = advicePushService.getUserAdvices(userId);
            
            assertNotNull(actualAdvices);
            assertEquals(expectedAdvices.size(), actualAdvices.size());
        }

        @Test
        @DisplayName("查询未读建议")
        void testGetUnreadAdvices() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> unreadAdvices = Arrays.asList(
                    TestDataBuilder.buildHighPriorityAdvice(userId),
                    TestDataBuilder.buildHighPriorityAdvice(userId)
            );
            
            when(healthAdviceRepository.findByUserIdAndReadStatus(userId, "unread"))
                    .thenReturn(unreadAdvices);

            List<HealthAdvice> result = advicePushService.getUnreadAdvices(userId);
            
            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("标记建议已读")
        void testMarkAdviceAsRead() {
            String adviceId = "advice_test_001";
            HealthAdvice advice = TestDataBuilder.buildHighPriorityAdvice("user_001");
            advice.setAdviceId(adviceId);
            
            when(healthAdviceRepository.findById(adviceId)).thenReturn(Optional.of(advice));
            
            advicePushService.markAdviceAsRead(adviceId);
            
            assertEquals("read", advice.getReadStatus());
            verify(healthAdviceRepository, times(1)).save(advice);
        }

        @Test
        @DisplayName("推送待发送建议")
        void testPushPendingAdvices() {
            String userId = TestDataBuilder.getDefaultUserId();
            List<HealthAdvice> pendingAdvices = Arrays.asList(
                    TestDataBuilder.buildHighPriorityAdvice(userId),
                    TestDataBuilder.buildMediumPriorityAdvice(userId)
            );
            
            when(healthAdviceRepository.findByUserIdAndPushedFalse(userId))
                    .thenReturn(pendingAdvices);

            advicePushService.pushPendingAdvices(userId);
            
            verify(healthAdviceRepository, times(2)).save(any(HealthAdvice.class));
        }
    }
}
