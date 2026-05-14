package com.memberscore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memberscore.entity.*;
import com.memberscore.enums.ExpirePolicyType;
import com.memberscore.enums.ValidationType;
import com.memberscore.repository.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {
    
    private final LevelConfigRepository levelConfigRepository;
    private final PointRuleRepository pointRuleRepository;
    private final ValidationRuleRepository validationRuleRepository;
    private final ExpirePolicyConfigRepository expirePolicyConfigRepository;
    private final ObjectMapper objectMapper;
    
    @PostConstruct
    @Transactional
    public void init() {
        initExpirePolicies();
        initValidationRules();
        initLevelConfigs();
        initPointRules();
    }
    
    private void initExpirePolicies() {
        if (expirePolicyConfigRepository.count() > 0) {
            log.info("过期策略已存在，跳过初始化");
            return;
        }
        
        try {
            String levelExpireConfig = objectMapper.writeValueAsString(Map.of(
                    "bronze", 180,
                    "silver", 270,
                    "gold", 365,
                    "platinum", 730
            ));
            
            ExpirePolicyConfig fixedPolicy = ExpirePolicyConfig.builder()
                    .policyId("exp_fixed_365")
                    .policyName("固定365天过期")
                    .policyType(ExpirePolicyType.FIXED_TERM)
                    .isDefault(false)
                    .fixedExpireDays(365)
                    .isEnabled(true)
                    .description("固定期限策略：所有积分365天后过期")
                    .build();
            
            ExpirePolicyConfig flexiblePolicy = ExpirePolicyConfig.builder()
                    .policyId("exp_flexible")
                    .policyName("灵活期限")
                    .policyType(ExpirePolicyType.FLEXIBLE_TERM)
                    .isDefault(false)
                    .flexibleBaseDays(180)
                    .flexibleMaxDays(365)
                    .pointThreshold(1000)
                    .isEnabled(true)
                    .description("灵活期限策略：积分越多，有效期越长(180-365天)")
                    .build();
            
            ExpirePolicyConfig levelPolicy = ExpirePolicyConfig.builder()
                    .policyId("exp_level_diff")
                    .policyName("等级差异期限")
                    .policyType(ExpirePolicyType.LEVEL_DIFFERENCE)
                    .isDefault(true)
                    .fixedExpireDays(365)
                    .levelExpireConfig(levelExpireConfig)
                    .isEnabled(true)
                    .description("等级差异策略：青铜180天、白银270天、黄金365天、铂金730天")
                    .build();
            
            ExpirePolicyConfig neverExpirePolicy = ExpirePolicyConfig.builder()
                    .policyId("exp_never")
                    .policyName("永不过期")
                    .policyType(ExpirePolicyType.NEVER_EXPIRE)
                    .isDefault(false)
                    .isEnabled(true)
                    .description("永久有效策略：积分永不过期")
                    .build();
            
            expirePolicyConfigRepository.saveAll(List.of(fixedPolicy, flexiblePolicy, levelPolicy, neverExpirePolicy));
            log.info("初始化过期策略完成: 4条策略");
        } catch (Exception e) {
            log.error("初始化过期策略失败", e);
        }
    }
    
    private void initValidationRules() {
        if (validationRuleRepository.count() > 0) {
            log.info("校验规则已存在，跳过初始化");
            return;
        }
        
        try {
            ValidationRule purchaseValidation = ValidationRule.builder()
                    .ruleId("val_purchase")
                    .ruleName("购物积分校验规则")
                    .validationType(ValidationType.AMOUNT_RELATED)
                    .sourceType("purchase")
                    .minAmount(1)
                    .maxAmount(100000)
                    .amountFactor(1.0)
                    .isEnabled(true)
                    .description("购物积分：金额关联校验，1元=1积分，最低1元最高10万元")
                    .build();
            
            ValidationRule signValidation = ValidationRule.builder()
                    .ruleId("val_sign")
                    .ruleName("签到积分校验规则")
                    .validationType(ValidationType.FIXED_AMOUNT)
                    .sourceType("sign")
                    .fixedPoints(10)
                    .isEnabled(true)
                    .description("签到积分：固定10积分/天")
                    .build();
            
            ValidationRule shareValidation = ValidationRule.builder()
                    .ruleId("val_share")
                    .ruleName("分享积分校验规则")
                    .validationType(ValidationType.FIXED_AMOUNT)
                    .sourceType("share")
                    .fixedPoints(20)
                    .timeWindowMinutes(1440)
                    .maxPointsPerWindow(200)
                    .isEnabled(true)
                    .description("分享积分：固定20积分/次，每日最多200积分")
                    .build();
            
            ValidationRule commentValidation = ValidationRule.builder()
                    .ruleId("val_comment")
                    .ruleName("评价积分校验规则")
                    .validationType(ValidationType.FIXED_AMOUNT)
                    .sourceType("comment")
                    .fixedPoints(15)
                    .isEnabled(true)
                    .description("评价积分：固定15积分/条")
                    .build();
            
            ValidationRule promotionValidation = ValidationRule.builder()
                    .ruleId("val_promotion")
                    .ruleName("活动积分校验规则")
                    .validationType(ValidationType.TIME_RELATED)
                    .sourceType("promotion")
                    .timeWindowMinutes(60)
                    .maxPointsPerWindow(1000)
                    .isEnabled(true)
                    .description("活动积分：时间窗口校验，每小时最多1000积分")
                    .build();
            
            validationRuleRepository.saveAll(List.of(
                    purchaseValidation, signValidation, shareValidation, 
                    commentValidation, promotionValidation
            ));
            log.info("初始化校验规则完成: 5条规则");
        } catch (Exception e) {
            log.error("初始化校验规则失败", e);
        }
    }
    
    private void initLevelConfigs() {
        if (levelConfigRepository.count() > 0) {
            log.info("等级配置已存在，跳过初始化");
            return;
        }
        
        try {
            String bronzeBenefits = objectMapper.writeValueAsString(List.of(
                    Map.of("type", "birthday", "content", "生日双倍积分"),
                    Map.of("type", "general", "content", "基础会员权益")
            ));
            
            String silverBenefits = objectMapper.writeValueAsString(List.of(
                    Map.of("type", "birthday", "content", "生日双倍积分"),
                    Map.of("type", "discount", "content", "购物折扣5%"),
                    Map.of("type", "service", "content", "优先客服")
            ));
            
            String goldBenefits = objectMapper.writeValueAsString(List.of(
                    Map.of("type", "birthday", "content", "生日三倍积分"),
                    Map.of("type", "discount", "content", "购物折扣10%"),
                    Map.of("type", "service", "content", "专属客服"),
                    Map.of("type", "points", "content", "积分加倍")
            ));
            
            String platinumBenefits = objectMapper.writeValueAsString(List.of(
                    Map.of("type", "birthday", "content", "生日五倍积分"),
                    Map.of("type", "discount", "content", "购物折扣15%"),
                    Map.of("type", "service", "content", "VIP专属客服"),
                    Map.of("type", "points", "content", "积分翻倍"),
                    Map.of("type", "gift", "content", "年度礼品")
            ));
            
            LevelConfig bronze = LevelConfig.builder()
                    .levelId("bronze")
                    .levelName("青铜会员")
                    .levelPointsRequired(0)
                    .levelBenefits(bronzeBenefits)
                    .levelOrder(1)
                    .pointMultiplier(1.0)
                    .isEnabled(true)
                    .build();
            
            LevelConfig silver = LevelConfig.builder()
                    .levelId("silver")
                    .levelName("白银会员")
                    .levelPointsRequired(1000)
                    .levelBenefits(silverBenefits)
                    .levelOrder(2)
                    .pointMultiplier(1.2)
                    .isEnabled(true)
                    .build();
            
            LevelConfig gold = LevelConfig.builder()
                    .levelId("gold")
                    .levelName("黄金会员")
                    .levelPointsRequired(3000)
                    .levelBenefits(goldBenefits)
                    .levelOrder(3)
                    .pointMultiplier(1.5)
                    .isEnabled(true)
                    .build();
            
            LevelConfig platinum = LevelConfig.builder()
                    .levelId("platinum")
                    .levelName("铂金会员")
                    .levelPointsRequired(10000)
                    .levelBenefits(platinumBenefits)
                    .levelOrder(4)
                    .pointMultiplier(2.0)
                    .isEnabled(true)
                    .build();
            
            levelConfigRepository.saveAll(List.of(bronze, silver, gold, platinum));
            log.info("初始化等级配置完成: 4个等级");
        } catch (Exception e) {
            log.error("初始化等级配置失败", e);
        }
    }
    
    private void initPointRules() {
        if (pointRuleRepository.count() > 0) {
            log.info("积分规则已存在，跳过初始化");
            return;
        }
        
        PointRule purchaseRule = PointRule.builder()
                .ruleId("rule_purchase")
                .ruleName("购物积分规则")
                .ruleType("purchase")
                .rulePoints(1)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .validationRuleId("val_purchase")
                .expirePolicyId("exp_level_diff")
                .ruleDescription("每消费1元获得1积分")
                .build();
        
        PointRule signRule = PointRule.builder()
                .ruleId("rule_sign")
                .ruleName("签到积分规则")
                .ruleType("sign")
                .rulePoints(10)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .validationRuleId("val_sign")
                .expirePolicyId("exp_level_diff")
                .ruleDescription("每日签到获得10积分")
                .build();
        
        PointRule shareRule = PointRule.builder()
                .ruleId("rule_share")
                .ruleName("分享积分规则")
                .ruleType("share")
                .rulePoints(20)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .validationRuleId("val_share")
                .expirePolicyId("exp_level_diff")
                .ruleDescription("分享商品获得20积分")
                .build();
        
        PointRule commentRule = PointRule.builder()
                .ruleId("rule_comment")
                .ruleName("评价积分规则")
                .ruleType("comment")
                .rulePoints(15)
                .ruleMultiplier(1.0)
                .ruleEnabled(true)
                .validationRuleId("val_comment")
                .expirePolicyId("exp_level_diff")
                .ruleDescription("商品评价获得15积分")
                .build();
        
        PointRule promotionRule = PointRule.builder()
                .ruleId("rule_promotion")
                .ruleName("促销活动积分规则")
                .ruleType("promotion")
                .rulePoints(5)
                .ruleMultiplier(2.0)
                .ruleEnabled(true)
                .validationRuleId("val_promotion")
                .expirePolicyId("exp_flexible")
                .ruleDescription("促销活动双倍积分")
                .build();
        
        pointRuleRepository.saveAll(List.of(purchaseRule, signRule, shareRule, commentRule, promotionRule));
        log.info("初始化积分规则完成: 5条规则");
    }
}
