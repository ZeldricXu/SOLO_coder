package com.healthtrack.testbuilder;

import com.healthtrack.dto.HealthDataReportRequest;
import com.healthtrack.entity.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private static final String DEFAULT_USER_ID = "user_test_001";
    private static final String DEFAULT_DEVICE_ID = "device_test_01";

    public static HealthDataReportRequest buildNormalHeartRateRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType("heart_rate");
        request.setDataValue(75.0);
        request.setDataUnit("bpm");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildAbnormalHeartRateRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType("heart_rate");
        request.setDataValue(150.0);
        request.setDataUnit("bpm");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildNormalWeightRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType("weight");
        request.setDataValue(65.0);
        request.setDataUnit("kg");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildAbnormalWeightRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType("weight");
        request.setDataValue(350.0);
        request.setDataUnit("kg");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildNormalBloodPressureRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType("blood_pressure_systolic");
        request.setDataValue(120.0);
        request.setDataUnit("mmHg");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildInvalidDataRequest() {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId("");
        request.setDataType(null);
        request.setDataValue(null);
        return request;
    }

    public static HealthDataReportRequest buildRequestWithUserId(String userId) {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(userId);
        request.setDataType("heart_rate");
        request.setDataValue(80.0);
        request.setDataUnit("bpm");
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthDataReportRequest buildRequestWithType(String dataType, Double value) {
        HealthDataReportRequest request = new HealthDataReportRequest();
        request.setUserId(DEFAULT_USER_ID);
        request.setDataType(dataType);
        request.setDataValue(value);
        request.setDeviceId(DEFAULT_DEVICE_ID);
        return request;
    }

    public static HealthData buildHealthData(String userId, String dataType, Double value, String quality) {
        HealthData data = new HealthData();
        data.setDataId("data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        data.setUserId(userId);
        data.setDataType(dataType);
        data.setDataValue(value);
        data.setQuality(quality);
        data.setCollectedAt(LocalDateTime.now());
        data.setDeviceId(DEFAULT_DEVICE_ID);
        return data;
    }

    public static HealthData buildNormalHealthData() {
        return buildHealthData(DEFAULT_USER_ID, "heart_rate", 75.0, "good");
    }

    public static HealthData buildAbnormalHealthData() {
        return buildHealthData(DEFAULT_USER_ID, "heart_rate", 150.0, "abnormal");
    }

    public static List<HealthData> buildRecentHealthDataList(String userId, String dataType, int count) {
        List<HealthData> dataList = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now().minusDays(7);
        
        for (int i = 0; i < count; i++) {
            HealthData data = new HealthData();
            data.setDataId("data_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            data.setUserId(userId);
            data.setDataType(dataType);
            data.setDataValue(70.0 + Math.random() * 20);
            data.setQuality("good");
            data.setCollectedAt(baseTime.plusDays(i));
            data.setDeviceId(DEFAULT_DEVICE_ID);
            dataList.add(data);
        }
        return dataList;
    }

    public static HealthIndicator buildHeartRateIndicator(String userId) {
        HealthIndicator indicator = new HealthIndicator();
        indicator.setIndicatorId("indicator_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        indicator.setUserId(userId);
        indicator.setIndicatorType("heart_rate");
        indicator.setCurrentValue(75.0);
        indicator.setAverageValue(72.0);
        indicator.setTargetValue(70.0);
        indicator.setMaxValue(85.0);
        indicator.setMinValue(65.0);
        indicator.setTrend("stable");
        indicator.setStatus("normal");
        indicator.setUpdatedAt(LocalDateTime.now());
        return indicator;
    }

    public static HealthIndicator buildAbnormalHeartRateIndicator(String userId) {
        HealthIndicator indicator = buildHeartRateIndicator(userId);
        indicator.setCurrentValue(150.0);
        indicator.setStatus("abnormal");
        indicator.setTrend("rising");
        return indicator;
    }

    public static HealthIndicator buildIndicator(String userId, String type, Double currentValue, String status) {
        HealthIndicator indicator = new HealthIndicator();
        indicator.setIndicatorId("indicator_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        indicator.setUserId(userId);
        indicator.setIndicatorType(type);
        indicator.setCurrentValue(currentValue);
        indicator.setAverageValue(currentValue);
        indicator.setStatus(status);
        indicator.setTrend("stable");
        indicator.setUpdatedAt(LocalDateTime.now());
        return indicator;
    }

    public static HealthGoal buildWeightGoal(String userId) {
        HealthGoal goal = new HealthGoal();
        goal.setGoalId("goal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        goal.setUserId(userId);
        goal.setGoalType("weight");
        goal.setStartValue(70.0);
        goal.setCurrentValue(67.5);
        goal.setTargetValue(65.0);
        goal.setDeadline(LocalDate.now().plusMonths(1));
        goal.setStatus("in_progress");
        goal.setProgress(50);
        goal.setDescription("减重目标");
        return goal;
    }

    public static HealthGoal buildAchievableWeightGoal(String userId) {
        HealthGoal goal = buildWeightGoal(userId);
        goal.setCurrentValue(64.5);
        return goal;
    }

    public static HealthGoal buildLaggingGoal(String userId) {
        HealthGoal goal = buildWeightGoal(userId);
        goal.setProgress(30);
        goal.setCurrentValue(68.5);
        return goal;
    }

    public static HealthAdvice buildAdvice(String userId, String adviceType, String priority) {
        HealthAdvice advice = new HealthAdvice();
        advice.setAdviceId("advice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        advice.setUserId(userId);
        advice.setAdviceType(adviceType);
        advice.setAdviceContent(generateAdviceContent(adviceType, priority));
        advice.setPriority(priority);
        advice.setBasedIndicators("heart_rate");
        advice.setReadStatus("unread");
        advice.setPushed(false);
        return advice;
    }

    public static HealthAdvice buildHighPriorityAdvice(String userId) {
        return buildAdvice(userId, "cardiovascular", "high");
    }

    public static HealthAdvice buildMediumPriorityAdvice(String userId) {
        return buildAdvice(userId, "goal", "medium");
    }

    public static HealthAdvice buildLowPriorityAdvice(String userId) {
        return buildAdvice(userId, "maintenance", "low");
    }

    public static List<HealthAdvice> buildRecentAdvices(String userId, String type, String priority, int count) {
        List<HealthAdvice> advices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HealthAdvice advice = buildAdvice(userId, type, priority);
            advice.setGeneratedAt(LocalDateTime.now().minusHours(i));
            advices.add(advice);
        }
        return advices;
    }

    public static HealthReminder buildMedicationReminder(String userId) {
        HealthReminder reminder = new HealthReminder();
        reminder.setReminderId("reminder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        reminder.setUserId(userId);
        reminder.setReminderType("medication");
        reminder.setReminderTime("08:00");
        reminder.setReminderContent("服用降压药");
        reminder.setFrequency("daily");
        reminder.setEnabled(true);
        return reminder;
    }

    public static HealthStatistics buildStatistics(String userId) {
        HealthStatistics stats = new HealthStatistics();
        stats.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        stats.setUserId(userId);
        stats.setStatDate(LocalDate.now());
        stats.setTotalRecords(10);
        stats.setNormalCount(8);
        stats.setAbnormalCount(2);
        stats.setGoalProgress(75);
        stats.setAvgHeartRate(72.0);
        stats.setAvgWeight(65.5);
        return stats;
    }

    public static HealthHistory buildHistory(String userId, String actionType) {
        HealthHistory history = new HealthHistory();
        history.setUserId(userId);
        history.setDataType("heart_rate");
        history.setActionType(actionType);
        history.setOldValue(70.0);
        history.setNewValue(75.0);
        history.setDescription("测试历史记录: " + actionType);
        history.setRecordedAt(LocalDateTime.now());
        return history;
    }

    private static String generateAdviceContent(String adviceType, String priority) {
        switch (adviceType) {
            case "cardiovascular":
                return "您的心率异常，建议注意休息，保持良好的生活习惯。";
            case "weight":
                return "您的体重管理需要关注，建议保持均衡饮食和适量运动。";
            case "goal":
                return "您的目标进度需要关注，继续努力达成目标。";
            case "maintenance":
                return "您的健康指标保持良好，继续保持健康的生活方式。";
            case "sleep":
                return "您的睡眠质量需要关注，建议保持规律作息。";
            default:
                return "建议您关注健康指标变化，保持良好的生活习惯。";
        }
    }

    public static String getDefaultUserId() {
        return DEFAULT_USER_ID;
    }

    public static String getDefaultDeviceId() {
        return DEFAULT_DEVICE_ID;
    }
}
