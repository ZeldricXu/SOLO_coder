package com.memberscore.testdata;

import com.memberscore.entity.*;
import com.memberscore.enums.MemberStatus;
import com.memberscore.enums.PointType;
import com.memberscore.enums.BenefitStatus;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static Member buildBronzeMember() {
        return buildMember("member_001", "user_10001", "bronze", 0, 0);
    }

    public static Member buildSilverMember() {
        return buildMember("member_002", "user_10002", "silver", 1500, 1500);
    }

    public static Member buildGoldMember() {
        return buildMember("member_003", "user_10003", "gold", 5000, 5000);
    }

    public static Member buildPlatinumMember() {
        return buildMember("member_004", "user_10004", "platinum", 15000, 15000);
    }

    public static Member buildMember(String memberId, String userId, String level, 
                                     int totalPoints, int availablePoints) {
        return Member.builder()
                .id(1L)
                .memberId(memberId)
                .userId(userId)
                .memberLevel(level)
                .totalPoints(totalPoints)
                .availablePoints(availablePoints)
                .usedPoints(totalPoints - availablePoints)
                .memberStatus(MemberStatus.ACTIVE)
                .registeredAt(LocalDateTime.now().minusMonths(6))
                .levelUpdatedAt(LocalDateTime.now().minusMonths(1))
                .build();
    }

    public static Member buildMemberWithAvailablePoints(int availablePoints) {
        return Member.builder()
                .id(1L)
                .memberId("member_test_" + UUID.randomUUID().toString().substring(0, 6))
                .userId("user_test_" + UUID.randomUUID().toString().substring(0, 6))
                .memberLevel("bronze")
                .totalPoints(availablePoints + 100)
                .availablePoints(availablePoints)
                .usedPoints(100)
                .memberStatus(MemberStatus.ACTIVE)
                .registeredAt(LocalDateTime.now().minusMonths(3))
                .build();
    }

    public static PointRule buildPurchaseRule() {
        return PointRule.builder()
                .id(1L)
                .ruleId("rule_purchase")
                .ruleName("购物积分规则")
                .ruleType("purchase")
                .rulePoints(1)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .ruleDescription("每消费1元获得1积分")
                .createdAt(LocalDateTime.now().minusMonths(3))
                .build();
    }

    public static PointRule buildSignRule() {
        return PointRule.builder()
                .id(2L)
                .ruleId("rule_sign")
                .ruleName("签到积分规则")
                .ruleType("sign")
                .rulePoints(10)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .ruleDescription("每日签到获得10积分")
                .createdAt(LocalDateTime.now().minusMonths(3))
                .build();
    }

    public static PointRule buildPromotionRule() {
        return PointRule.builder()
                .id(3L)
                .ruleId("rule_promotion")
                .ruleName("促销活动积分规则")
                .ruleType("promotion")
                .rulePoints(5)
                .ruleMultiplier(2.0)
                .ruleEnabled(true)
                .ruleDescription("促销活动双倍积分")
                .startDate(LocalDateTime.now().minusDays(7))
                .endDate(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now().minusMonths(1))
                .build();
    }

    public static PointRule buildDisabledRule() {
        return PointRule.builder()
                .id(4L)
                .ruleId("rule_disabled")
                .ruleName("已禁用规则")
                .ruleType("old_rule")
                .rulePoints(100)
                .ruleMultiplier(1.0)
                .ruleEnabled(false)
                .ruleDescription("已禁用的积分规则")
                .createdAt(LocalDateTime.now().minusYears(1))
                .build();
    }

    public static PointRule buildExpiredRule() {
        return PointRule.builder()
                .id(5L)
                .ruleId("rule_expired")
                .ruleName("已过期活动规则")
                .ruleType("expired_promo")
                .rulePoints(10)
                .ruleMultiplier(3.0)
                .ruleEnabled(true)
                .ruleDescription("已过期的活动积分规则")
                .startDate(LocalDateTime.now().minusMonths(2))
                .endDate(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now().minusMonths(2))
                .build();
    }

    public static LevelConfig buildBronzeLevelConfig() {
        return LevelConfig.builder()
                .id(1L)
                .levelId("bronze")
                .levelName("青铜会员")
                .levelPointsRequired(0)
                .levelBenefits("[{\"type\":\"birthday\",\"content\":\"生日双倍积分\"}]")
                .levelOrder(1)
                .pointMultiplier(1.0)
                .isEnabled(true)
                .build();
    }

    public static LevelConfig buildSilverLevelConfig() {
        return LevelConfig.builder()
                .id(2L)
                .levelId("silver")
                .levelName("白银会员")
                .levelPointsRequired(1000)
                .levelBenefits("[{\"type\":\"discount\",\"content\":\"购物折扣5%\"}]")
                .levelOrder(2)
                .pointMultiplier(1.2)
                .isEnabled(true)
                .build();
    }

    public static LevelConfig buildGoldLevelConfig() {
        return LevelConfig.builder()
                .id(3L)
                .levelId("gold")
                .levelName("黄金会员")
                .levelPointsRequired(3000)
                .levelBenefits("[{\"type\":\"discount\",\"content\":\"购物折扣10%\"},{\"type\":\"service\",\"content\":\"专属客服\"}]")
                .levelOrder(3)
                .pointMultiplier(1.5)
                .isEnabled(true)
                .build();
    }

    public static LevelConfig buildPlatinumLevelConfig() {
        return LevelConfig.builder()
                .id(4L)
                .levelId("platinum")
                .levelName("铂金会员")
                .levelPointsRequired(10000)
                .levelBenefits("[{\"type\":\"discount\",\"content\":\"购物折扣15%\"}]")
                .levelOrder(4)
                .pointMultiplier(2.0)
                .isEnabled(true)
                .build();
    }

    public static List<LevelConfig> buildAllLevelConfigs() {
        List<LevelConfig> configs = new ArrayList<>();
        configs.add(buildBronzeLevelConfig());
        configs.add(buildSilverLevelConfig());
        configs.add(buildGoldLevelConfig());
        configs.add(buildPlatinumLevelConfig());
        return configs;
    }

    public static PointRecord buildEarnPointRecord(String memberId, int amount, String source) {
        return PointRecord.builder()
                .id(1L)
                .pointId("point_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .memberId(memberId)
                .pointType(PointType.EARN)
                .pointAmount(amount)
                .pointSource(source)
                .pointBalance(amount)
                .expireAt(LocalDate.now().plusYears(1))
                .isExpired(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static PointRecord buildConsumePointRecord(String memberId, int amount, String consumeType) {
        return PointRecord.builder()
                .id(2L)
                .pointId("point_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .memberId(memberId)
                .pointType(PointType.CONSUME)
                .pointAmount(amount)
                .consumeType(consumeType)
                .pointBalance(0)
                .isExpired(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static PointRecord buildExpiredPointRecord(String memberId, int amount) {
        return PointRecord.builder()
                .id(3L)
                .pointId("point_expired_" + UUID.randomUUID().toString().substring(0, 6))
                .memberId(memberId)
                .pointType(PointType.EARN)
                .pointAmount(amount)
                .pointSource("purchase")
                .pointBalance(amount)
                .expireAt(LocalDate.now().minusDays(1))
                .isExpired(false)
                .createdAt(LocalDateTime.now().minusYears(1))
                .build();
    }

    public static BenefitRecord buildActiveBenefit(String memberId, String levelId, String type, String content) {
        return BenefitRecord.builder()
                .id(1L)
                .benefitId("benefit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .memberId(memberId)
                .levelId(levelId)
                .benefitType(type)
                .benefitContent(content)
                .benefitStatus(BenefitStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusYears(1))
                .build();
    }

    public static PointStat buildDailyStat(LocalDate date) {
        return PointStat.builder()
                .id(1L)
                .statId("stat_" + UUID.randomUUID().toString().substring(0, 8))
                .statDate(date)
                .earnCount(10)
                .earnPoints(5000)
                .consumeCount(5)
                .consumePoints(2000)
                .build();
    }

    public static class MemberScenario {
        public static Member aboutToUpgradeToSilver() {
            return buildMember("member_upgrade_silver", "user_upgrade_1", "bronze", 990, 990);
        }

        public static Member exactlyAtSilverThreshold() {
            return buildMember("member_silver_exact", "user_silver_1", "bronze", 1000, 1000);
        }

        public static Member aboutToUpgradeToGold() {
            return buildMember("member_upgrade_gold", "user_upgrade_2", "silver", 2950, 2950);
        }

        public static Member exactlyAtGoldThreshold() {
            return buildMember("member_gold_exact", "user_gold_1", "silver", 3000, 3000);
        }

        public static Member multiLevelJumpCandidate() {
            return buildMember("member_multi_jump", "user_jump_1", "bronze", 15000, 15000);
        }
    }

    public static class PointsScenario {
        public static int smallPurchaseAmount = 100;
        public static int mediumPurchaseAmount = 1000;
        public static int largePurchaseAmount = 10000;
        
        public static int sufficientBalance = 5000;
        public static int insufficientBalance = 100;
        public static int exactBalance = 1000;
        public static int zeroBalance = 0;
    }
}
