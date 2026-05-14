package com.memberscore.service;

import com.memberscore.entity.PointRule;
import com.memberscore.repository.PointRuleRepository;
import com.memberscore.testdata.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("积分规则服务 - 单元测试")
class PointRuleServiceTest {

    @Mock
    private PointRuleRepository pointRuleRepository;

    @InjectMocks
    private PointRuleService pointRuleService;

    @Nested
    @DisplayName("积分规则匹配测试")
    class RuleMatchingTests {

        @Test
        @DisplayName("购物积分规则匹配成功")
        void testPurchaseRuleMatch_Success() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            
            when(pointRuleRepository.findActiveRuleByType(eq("purchase"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(purchaseRule));

            Optional<PointRule> result = pointRuleService.getActiveRule("purchase");

            assertTrue(result.isPresent());
            assertEquals("rule_purchase", result.get().getRuleId());
            assertEquals("purchase", result.get().getRuleType());
            verify(pointRuleRepository, times(1))
                    .findActiveRuleByType(eq("purchase"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("签到积分规则匹配成功")
        void testSignRuleMatch_Success() {
            PointRule signRule = TestDataBuilder.buildSignRule();
            
            when(pointRuleRepository.findActiveRuleByType(eq("sign"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(signRule));

            Optional<PointRule> result = pointRuleService.getActiveRule("sign");

            assertTrue(result.isPresent());
            assertEquals("rule_sign", result.get().getRuleId());
            assertEquals(Integer.valueOf(10), result.get().getRulePoints());
        }

        @Test
        @DisplayName("无效积分来源 - 规则匹配失败")
        void testInvalidSource_RuleNotFound() {
            when(pointRuleRepository.findActiveRuleByType(eq("invalid_source"), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            Optional<PointRule> result = pointRuleService.getActiveRule("invalid_source");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("活动积分时间关联校验 - 有效期内匹配成功")
        void testPromotionRule_WithinValidPeriod() {
            PointRule promotionRule = TestDataBuilder.buildPromotionRule();
            
            when(pointRuleRepository.findActiveRuleByType(eq("promotion"), any(LocalDateTime.class)))
                    .thenReturn(Optional.of(promotionRule));

            Optional<PointRule> result = pointRuleService.getActiveRule("promotion");

            assertTrue(result.isPresent());
            assertTrue(result.get().getRuleEnabled());
            assertNotNull(result.get().getStartDate());
            assertNotNull(result.get().getEndDate());
            assertTrue(LocalDateTime.now().isAfter(result.get().getStartDate()));
            assertTrue(LocalDateTime.now().isBefore(result.get().getEndDate()));
        }

        @Test
        @DisplayName("活动积分时间关联校验 - 已过期规则不匹配")
        void testExpiredRule_ShouldNotMatch() {
            when(pointRuleRepository.findActiveRuleByType(eq("expired_promo"), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            Optional<PointRule> result = pointRuleService.getActiveRule("expired_promo");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("已禁用规则不匹配")
        void testDisabledRule_ShouldNotMatch() {
            when(pointRuleRepository.findActiveRuleByType(eq("old_rule"), any(LocalDateTime.class)))
                    .thenReturn(Optional.empty());

            Optional<PointRule> result = pointRuleService.getActiveRule("old_rule");

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("积分计算校验测试")
    class PointsCalculationTests {

        @Test
        @DisplayName("购物积分金额关联校验 - 基础计算")
        void testPurchasePointsCalculation_Basic() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 100;
            double levelMultiplier = 1.0;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(100, points);
        }

        @Test
        @DisplayName("购物积分金额关联校验 - 大额消费")
        void testPurchasePointsCalculation_LargeAmount() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 10000;
            double levelMultiplier = 1.0;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(10000, points);
        }

        @Test
        @DisplayName("签到积分 - 固定积分计算")
        void testSignPointsCalculation_FixedPoints() {
            PointRule signRule = TestDataBuilder.buildSignRule();
            int baseAmount = 1;
            double levelMultiplier = 1.0;

            int points = pointRuleService.calculatePoints(signRule, baseAmount, levelMultiplier);

            assertEquals(10, points);
        }

        @Test
        @DisplayName("等级倍率加成 - 白银会员1.2倍")
        void testPointsCalculation_WithSilverMultiplier() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 100;
            double levelMultiplier = 1.2;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(120, points);
        }

        @Test
        @DisplayName("等级倍率加成 - 黄金会员1.5倍")
        void testPointsCalculation_WithGoldMultiplier() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 100;
            double levelMultiplier = 1.5;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(150, points);
        }

        @Test
        @DisplayName("等级倍率加成 - 铂金会员2.0倍")
        void testPointsCalculation_WithPlatinumMultiplier() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 100;
            double levelMultiplier = 2.0;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(200, points);
        }

        @Test
        @DisplayName("活动积分倍率叠加 - 促销活动2倍 + 黄金会员1.5倍 = 3倍")
        void testPointsCalculation_WithPromotionAndLevelMultiplier() {
            PointRule promotionRule = TestDataBuilder.buildPromotionRule();
            int baseAmount = 100;
            double levelMultiplier = 1.5;

            int points = pointRuleService.calculatePoints(promotionRule, baseAmount, levelMultiplier);

            assertEquals(1500, points);
        }

        @Test
        @DisplayName("规则为null时返回0积分")
        void testPointsCalculation_NullRuleReturnsZero() {
            int points = pointRuleService.calculatePoints(null, 100, 1.0);

            assertEquals(0, points);
        }

        @Test
        @DisplayName("四舍五入校验 - 0.5及以上进位")
        void testPointsCalculation_RoundingUp() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 1;
            double levelMultiplier = 1.6;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(2, points);
        }

        @Test
        @DisplayName("四舍五入校验 - 0.5以下舍去")
        void testPointsCalculation_RoundingDown() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            int baseAmount = 1;
            double levelMultiplier = 1.4;

            int points = pointRuleService.calculatePoints(purchaseRule, baseAmount, levelMultiplier);

            assertEquals(1, points);
        }
    }

    @Nested
    @DisplayName("积分来源类型差异校验")
    class SourceTypeDifferenceTests {

        @Test
        @DisplayName("购物积分 - 金额关联校验")
        void testPurchaseSource_AmountRelated() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            
            int points1 = pointRuleService.calculatePoints(purchaseRule, 50, 1.0);
            int points2 = pointRuleService.calculatePoints(purchaseRule, 100, 1.0);
            int points3 = pointRuleService.calculatePoints(purchaseRule, 200, 1.0);

            assertEquals(50, points1);
            assertEquals(100, points2);
            assertEquals(200, points3);
            assertTrue(points2 > points1);
            assertTrue(points3 > points2);
        }

        @Test
        @DisplayName("签到积分 - 固定积分校验")
        void testSignSource_FixedPoints() {
            PointRule signRule = TestDataBuilder.buildSignRule();
            
            int points1 = pointRuleService.calculatePoints(signRule, 1, 1.0);
            int points2 = pointRuleService.calculatePoints(signRule, 100, 1.0);

            assertEquals(10, points1);
            assertEquals(10, points2);
            assertEquals(points1, points2);
        }

        @Test
        @DisplayName("不同来源积分规则比较")
        void testDifferentSources_CompareRules() {
            PointRule purchaseRule = TestDataBuilder.buildPurchaseRule();
            PointRule signRule = TestDataBuilder.buildSignRule();
            PointRule promotionRule = TestDataBuilder.buildPromotionRule();

            assertEquals(Integer.valueOf(1), purchaseRule.getRulePoints());
            assertEquals(Integer.valueOf(10), signRule.getRulePoints());
            assertEquals(Integer.valueOf(5), promotionRule.getRulePoints());

            assertEquals(Double.valueOf(1.0), purchaseRule.getRuleMultiplier());
            assertEquals(Double.valueOf(1.0), signRule.getRuleMultiplier());
            assertEquals(Double.valueOf(2.0), promotionRule.getRuleMultiplier());
        }
    }

    @Nested
    @DisplayName("规则状态管理测试")
    class RuleStateManagementTests {

        @Test
        @DisplayName("创建规则成功")
        void testCreateRule_Success() {
            PointRule newRule = PointRule.builder()
                    .ruleName("新活动规则")
                    .ruleType("new_activity")
                    .rulePoints(20)
                    .ruleMultiplier(1.5)
                    .ruleEnabled(true)
                    .build();
            
            when(pointRuleRepository.save(any(PointRule.class))).thenReturn(newRule);

            PointRule created = pointRuleService.createRule(newRule);

            assertNotNull(created);
            assertEquals("新活动规则", created.getRuleName());
            verify(pointRuleRepository, times(1)).save(any(PointRule.class));
        }

        @Test
        @DisplayName("创建规则时自动生成ruleId")
        void testCreateRule_AutoGenerateRuleId() {
            PointRule newRule = PointRule.builder()
                    .ruleName("测试规则")
                    .ruleType("test")
                    .rulePoints(10)
                    .ruleMultiplier(1.0)
                    .ruleEnabled(true)
                    .build();
            newRule.setRuleId(null);
            
            when(pointRuleRepository.save(any(PointRule.class))).thenAnswer(invocation -> {
                PointRule saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            PointRule created = pointRuleService.createRule(newRule);

            assertNotNull(created);
            assertNotNull(created.getRuleId());
            assertTrue(created.getRuleId().startsWith("rule_"));
        }

        @Test
        @DisplayName("禁用规则成功")
        void testDisableRule_Success() {
            PointRule rule = TestDataBuilder.buildPurchaseRule();
            
            when(pointRuleRepository.findByRuleId("rule_purchase")).thenReturn(Optional.of(rule));
            when(pointRuleRepository.save(any(PointRule.class))).thenReturn(rule);

            pointRuleService.disableRule("rule_purchase");

            assertFalse(rule.getRuleEnabled());
            assertNotNull(rule.getUpdatedAt());
            verify(pointRuleRepository, times(1)).save(rule);
        }

        @Test
        @DisplayName("启用规则成功")
        void testEnableRule_Success() {
            PointRule rule = TestDataBuilder.buildDisabledRule();
            rule.setRuleEnabled(false);
            
            when(pointRuleRepository.findByRuleId("rule_disabled")).thenReturn(Optional.of(rule));
            when(pointRuleRepository.save(any(PointRule.class))).thenReturn(rule);

            pointRuleService.enableRule("rule_disabled");

            assertTrue(rule.getRuleEnabled());
            verify(pointRuleRepository, times(1)).save(rule);
        }

        @Test
        @DisplayName("禁用不存在的规则抛出异常")
        void testDisableRule_NotFoundThrowsException() {
            when(pointRuleRepository.findByRuleId("non_existent")).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> {
                pointRuleService.disableRule("non_existent");
            });
        }
    }
}
