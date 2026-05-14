package com.adplatform.testdata;

import com.adplatform.dto.*;
import com.adplatform.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TestDataBuilder {
    private static final String DEFAULT_AD_ID = "ad_test_001";
    private static final String DEFAULT_AD_NAME = "测试广告-品牌推广";
    private static final String DEFAULT_AD_TYPE = "banner";
    private static final String DEFAULT_AD_CONTENT = "https://example.com/ad/banner.jpg";
    private static final String DEFAULT_ADVERTISER = "test_brand";
    private static final String DEFAULT_CHANNEL = "mobile_app";
    private static final String DEFAULT_POSITION = "home_banner";

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static AdInfo buildAdInfo() {
        return buildAdInfo(DEFAULT_AD_ID, DEFAULT_AD_NAME, DEFAULT_AD_TYPE, DEFAULT_AD_STATUS_PENDING);
    }

    public static AdInfo buildAdInfo(String adId) {
        return buildAdInfo(adId, DEFAULT_AD_NAME, DEFAULT_AD_TYPE, DEFAULT_AD_STATUS_PENDING);
    }

    public static AdInfo buildAdInfo(String adId, String status) {
        return buildAdInfo(adId, DEFAULT_AD_NAME, DEFAULT_AD_TYPE, status);
    }

    public static AdInfo buildAdInfo(String adId, String adName, String adType, String status) {
        return AdInfo.builder()
                .adId(adId)
                .adName(adName)
                .adType(adType)
                .adContent(DEFAULT_AD_CONTENT)
                .adStatus(status)
                .advertiser(DEFAULT_ADVERTISER)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static final String DEFAULT_AD_STATUS_PENDING = "pending";
    public static final String DEFAULT_AD_STATUS_APPROVED = "approved";
    public static final String DEFAULT_AD_STATUS_RUNNING = "running";
    public static final String DEFAULT_AD_STATUS_PAUSED = "paused";
    public static final String DEFAULT_AD_STATUS_ENDED = "ended";
    public static final String DEFAULT_AD_STATUS_REJECTED = "rejected";

    public static AdInfo buildPendingAd() {
        return buildAdInfo(generateId("ad"), DEFAULT_AD_STATUS_PENDING);
    }

    public static AdInfo buildApprovedAd() {
        return buildAdInfo(generateId("ad"), DEFAULT_AD_STATUS_APPROVED);
    }

    public static AdInfo buildRunningAd() {
        return buildAdInfo(generateId("ad"), DEFAULT_AD_STATUS_RUNNING);
    }

    public static AdInfo buildPausedAd() {
        return buildAdInfo(generateId("ad"), DEFAULT_AD_STATUS_PAUSED);
    }

    public static AdInfo buildEndedAd() {
        return buildAdInfo(generateId("ad"), DEFAULT_AD_STATUS_ENDED);
    }

    public static CreateAdRequest buildCreateAdRequest() {
        return CreateAdRequest.builder()
                .adName(DEFAULT_AD_NAME)
                .adType(DEFAULT_AD_TYPE)
                .adContent(DEFAULT_AD_CONTENT)
                .advertiser(DEFAULT_ADVERTISER)
                .build();
    }

    public static CreateAdRequest buildCreateAdRequest(String adName, String adType) {
        return CreateAdRequest.builder()
                .adName(adName)
                .adType(adType)
                .adContent(DEFAULT_AD_CONTENT)
                .advertiser(DEFAULT_ADVERTISER)
                .build();
    }

    public static PlacementRequest buildPlacementRequest(String adId) {
        return PlacementRequest.builder()
                .adId(adId)
                .placementChannel(DEFAULT_CHANNEL)
                .placementPosition(DEFAULT_POSITION)
                .placementStart(LocalDateTime.now())
                .placementEnd(LocalDateTime.now().plusDays(30))
                .budgetAmount(new BigDecimal("1000"))
                .budgetType("daily")
                .build();
    }

    public static PlacementRequest buildPlacementRequest(String adId, BigDecimal budgetAmount) {
        return PlacementRequest.builder()
                .adId(adId)
                .placementChannel(DEFAULT_CHANNEL)
                .placementPosition(DEFAULT_POSITION)
                .placementStart(LocalDateTime.now())
                .placementEnd(LocalDateTime.now().plusDays(30))
                .budgetAmount(budgetAmount)
                .budgetType("daily")
                .build();
    }

    public static PlacementRequest buildPlacementRequestWithTargeting(String adId) {
        Map<String, Object> targetConditions = new HashMap<>();
        targetConditions.put("age", "18-35");
        targetConditions.put("gender", "all");
        targetConditions.put("location", "北京");

        return PlacementRequest.builder()
                .adId(adId)
                .placementChannel(DEFAULT_CHANNEL)
                .placementPosition(DEFAULT_POSITION)
                .placementStart(LocalDateTime.now())
                .placementEnd(LocalDateTime.now().plusDays(30))
                .budgetAmount(new BigDecimal("1000"))
                .budgetType("daily")
                .targetType("demographic")
                .targetConditions(targetConditions)
                .build();
    }

    public static AdPlacement buildAdPlacement(String adId) {
        return AdPlacement.builder()
                .placementId(generateId("placement"))
                .adId(adId)
                .placementChannel(DEFAULT_CHANNEL)
                .placementPosition(DEFAULT_POSITION)
                .placementStart(LocalDateTime.now())
                .placementEnd(LocalDateTime.now().plusDays(30))
                .placementStatus("active")
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdPlacement buildAdPlacement(String adId, String status) {
        return AdPlacement.builder()
                .placementId(generateId("placement"))
                .adId(adId)
                .placementChannel(DEFAULT_CHANNEL)
                .placementPosition(DEFAULT_POSITION)
                .placementStart(LocalDateTime.now())
                .placementEnd(LocalDateTime.now().plusDays(30))
                .placementStatus(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdBudget buildAdBudget(String adId) {
        return buildAdBudget(adId, new BigDecimal("1000"));
    }

    public static AdBudget buildAdBudget(String adId, BigDecimal totalAmount) {
        return AdBudget.builder()
                .budgetId(generateId("budget"))
                .adId(adId)
                .budgetType("daily")
                .budgetAmount(totalAmount)
                .budgetConsumed(BigDecimal.ZERO)
                .budgetRemaining(totalAmount)
                .budgetThreshold(totalAmount.multiply(new BigDecimal("0.1")))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static AdBudget buildAdBudget(String adId, BigDecimal totalAmount, BigDecimal consumed) {
        return AdBudget.builder()
                .budgetId(generateId("budget"))
                .adId(adId)
                .budgetType("daily")
                .budgetAmount(totalAmount)
                .budgetConsumed(consumed)
                .budgetRemaining(totalAmount.subtract(consumed))
                .budgetThreshold(totalAmount.multiply(new BigDecimal("0.1")))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static AdBudget buildAdBudgetWithHighConsumption(String adId) {
        BigDecimal total = new BigDecimal("100");
        BigDecimal consumed = new BigDecimal("95");
        return buildAdBudget(adId, total, consumed);
    }

    public static AdBudget buildExhaustedBudget(String adId) {
        BigDecimal total = new BigDecimal("100");
        return AdBudget.builder()
                .budgetId(generateId("budget"))
                .adId(adId)
                .budgetType("daily")
                .budgetAmount(total)
                .budgetConsumed(total)
                .budgetRemaining(BigDecimal.ZERO)
                .budgetThreshold(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static AdTarget buildAdTarget(String adId) {
        Map<String, Object> targetConditions = new HashMap<>();
        targetConditions.put("age", "18-35");
        targetConditions.put("gender", "all");
        targetConditions.put("location", "北京");

        return AdTarget.builder()
                .targetId(generateId("target"))
                .adId(adId)
                .targetType("demographic")
                .targetConditions(targetConditions)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdTarget buildAdTarget(String adId, String targetType, Map<String, Object> conditions) {
        return AdTarget.builder()
                .targetId(generateId("target"))
                .adId(adId)
                .targetType(targetType)
                .targetConditions(conditions)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdEffect buildAdEffect(String adId) {
        return buildAdEffect(adId, LocalDate.now(), 1000L, 50L, 5L);
    }

    public static AdEffect buildAdEffect(String adId, LocalDate statDate, long exposure, long click, long conversion) {
        BigDecimal clickRate = exposure > 0 
                ? new BigDecimal(click).divide(new BigDecimal(exposure), 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal conversionRate = click > 0 
                ? new BigDecimal(conversion).divide(new BigDecimal(click), 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO;

        return AdEffect.builder()
                .effectId(generateId("effect"))
                .adId(adId)
                .statDate(statDate)
                .exposureCount(exposure)
                .clickCount(click)
                .clickRate(clickRate)
                .conversionCount(conversion)
                .conversionRate(conversionRate)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static AdEffect buildHighPerformanceEffect(String adId) {
        return buildAdEffect(adId, LocalDate.now(), 10000L, 800L, 80L);
    }

    public static AdEffect buildLowPerformanceEffect(String adId) {
        return buildAdEffect(adId, LocalDate.now(), 1000L, 5L, 0L);
    }

    public static AdEffect buildNoExposureEffect(String adId) {
        return buildAdEffect(adId, LocalDate.now(), 0L, 0L, 0L);
    }

    public static EffectEvent buildEffectEvent(String adId, String eventType) {
        return EffectEvent.builder()
                .adId(adId)
                .eventType(eventType)
                .position(DEFAULT_POSITION)
                .userInfo("user_123")
                .costAmount(new BigDecimal("0.5"))
                .build();
    }

    public static EffectEvent buildExposureEvent(String adId) {
        return buildEffectEvent(adId, "exposure");
    }

    public static EffectEvent buildClickEvent(String adId) {
        return buildEffectEvent(adId, "click");
    }

    public static EffectEvent buildConversionEvent(String adId) {
        return buildEffectEvent(adId, "conversion");
    }

    public static EffectEvent buildExposureEvent(String adId, BigDecimal cost) {
        return EffectEvent.builder()
                .adId(adId)
                .eventType("exposure")
                .position(DEFAULT_POSITION)
                .userInfo(null)
                .costAmount(cost)
                .build();
    }

    public static EffectEvent buildClickEvent(String adId, BigDecimal cost) {
        return EffectEvent.builder()
                .adId(adId)
                .eventType("click")
                .position(DEFAULT_POSITION)
                .userInfo("user_" + System.currentTimeMillis())
                .costAmount(cost)
                .build();
    }

    public static AdConsume buildAdConsume(String adId) {
        return AdConsume.builder()
                .consumeId(generateId("consume"))
                .adId(adId)
                .consumeType("click")
                .consumeAmount(new BigDecimal("0.5"))
                .consumeTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdConsume buildAdConsume(String adId, String type, BigDecimal amount) {
        return AdConsume.builder()
                .consumeId(generateId("consume"))
                .adId(adId)
                .consumeType(type)
                .consumeAmount(amount)
                .consumeTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static AdReport buildAdReport(String adId) {
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("exposureCount", 1000);
        reportData.put("clickCount", 50);
        reportData.put("clickRate", 0.05);
        reportData.put("conversionCount", 5);
        reportData.put("conversionRate", 0.1);

        return AdReport.builder()
                .reportId(generateId("report"))
                .adId(adId)
                .reportType("daily")
                .reportData(reportData)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    public static AdHistory buildAdHistory(String adId, String historyType) {
        Map<String, Object> historyData = new HashMap<>();
        historyData.put("adId", adId);
        historyData.put("operation", historyType);
        historyData.put("timestamp", LocalDateTime.now());

        return AdHistory.builder()
                .historyId(generateId("history"))
                .adId(adId)
                .historyType(historyType)
                .historyData(historyData)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static EffectQueryResponse buildEffectQueryResponse() {
        return EffectQueryResponse.builder()
                .exposureCount(10000L)
                .clickCount(500L)
                .clickRate(new BigDecimal("0.05"))
                .conversionCount(50L)
                .conversionRate(new BigDecimal("0.1"))
                .build();
    }

    public static EffectQueryResponse buildEffectQueryResponse(long exposure, long click, long conversion) {
        BigDecimal clickRate = exposure > 0 
                ? new BigDecimal(click).divide(new BigDecimal(exposure), 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal conversionRate = click > 0 
                ? new BigDecimal(conversion).divide(new BigDecimal(click), 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO;

        return EffectQueryResponse.builder()
                .exposureCount(exposure)
                .clickCount(click)
                .clickRate(clickRate)
                .conversionCount(conversion)
                .conversionRate(conversionRate)
                .build();
    }

    public static Map<String, Object> buildDemographicTargetConditions() {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("age", "18-35");
        conditions.put("gender", "all");
        conditions.put("location", "北京");
        return conditions;
    }

    public static Map<String, Object> buildGeographicTargetConditions() {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("location", "北京,上海,广州");
        conditions.put("cities", new String[]{"北京", "上海", "广州"});
        return conditions;
    }

    public static Map<String, Object> buildInterestTargetConditions() {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("interests", new String[]{"科技", "数码", "游戏"});
        return conditions;
    }

    public static Map<String, Object> buildBehaviorTargetConditions() {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("behaviors", new String[]{"购物", "浏览", "搜索"});
        conditions.put("purchaseFrequency", "high");
        return conditions;
    }
}
