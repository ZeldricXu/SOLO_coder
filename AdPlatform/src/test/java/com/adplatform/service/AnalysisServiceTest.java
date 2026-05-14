package com.adplatform.service;

import com.adplatform.dto.EffectQueryRequest;
import com.adplatform.dto.EffectQueryResponse;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("分析模块单元测试 - 效果分析逻辑")
class AnalysisServiceTest {

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private AdHistoryRepository adHistoryRepository;

    @InjectMocks
    private AnalysisService analysisService;

    private String testAdId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        testAdId = "ad_analysis_test_" + System.currentTimeMillis();
        today = LocalDate.now();
    }

    @Test
    @DisplayName("测试广告效果分析 - 高性能广告")
    void testAnalyzeAdPerformance_HighPerformance() {
        EffectQueryResponse highEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 800L, 80L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(highEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertNotNull(analysis);
        assertEquals(testAdId, analysis.get("adId"));
        
        assertEquals(10000L, analysis.get("exposureCount"));
        assertEquals(800L, analysis.get("clickCount"));
        assertEquals(50L, ((BigDecimal) analysis.get("conversionCount")).longValue());
        
        BigDecimal performanceScore = (BigDecimal) analysis.get("performanceScore");
        assertNotNull(performanceScore);
        assertTrue(performanceScore.compareTo(BigDecimal.ZERO) > 0);
        
        @SuppressWarnings("unchecked")
        String[] recommendations = (String[]) analysis.get("recommendations");
        assertNotNull(recommendations);
        assertTrue(recommendations.length > 0);
    }

    @Test
    @DisplayName("测试广告效果分析 - 低性能广告")
    void testAnalyzeAdPerformance_LowPerformance() {
        EffectQueryResponse lowEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 50L, 2L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(lowEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        @SuppressWarnings("unchecked")
        String[] recommendations = (String[]) analysis.get("recommendations");
        assertNotNull(recommendations);
        boolean hasOptimizationAdvice = false;
        for (String rec : recommendations) {
            if (rec.contains("优化") || rec.contains("偏低")) {
                hasOptimizationAdvice = true;
                break;
            }
        }
        assertTrue(hasOptimizationAdvice, "低性能广告应该有优化建议");
    }

    @Test
    @DisplayName("测试广告效果分析 - 无曝光广告")
    void testAnalyzeAdPerformance_NoExposure() {
        EffectQueryResponse noEffect = TestDataBuilder.buildEffectQueryResponse(0L, 0L, 0L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(noEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertEquals("no_impressions", analysis.get("status"));
        @SuppressWarnings("unchecked")
        String[] recommendations = (String[]) analysis.get("recommendations");
        assertNotNull(recommendations);
        assertTrue(recommendations.length > 0);
    }

    @Test
    @DisplayName("测试点击率计算 - 高点击率")
    void testAnalyzeClickQuality_HighCTR() {
        Map<String, Object> result = analysisService.analyzeClickQuality(1000L, 80L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0800"), result.get("clickRate"));
        assertEquals("excellent", result.get("quality"));
    }

    @Test
    @DisplayName("测试点击率计算 - 良好点击率")
    void testAnalyzeClickQuality_GoodCTR() {
        Map<String, Object> result = analysisService.analyzeClickQuality(1000L, 30L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0300"), result.get("clickRate"));
        assertEquals("good", result.get("quality"));
    }

    @Test
    @DisplayName("测试点击率计算 - 一般点击率")
    void testAnalyzeClickQuality_AverageCTR() {
        Map<String, Object> result = analysisService.analyzeClickQuality(1000L, 15L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0150"), result.get("clickRate"));
        assertEquals("average", result.get("quality"));
    }

    @Test
    @DisplayName("测试点击率计算 - 低点击率")
    void testAnalyzeClickQuality_PoorCTR() {
        Map<String, Object> result = analysisService.analyzeClickQuality(1000L, 5L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0050"), result.get("clickRate"));
        assertEquals("poor", result.get("quality"));
    }

    @Test
    @DisplayName("测试点击率计算 - 零曝光")
    void testAnalyzeClickQuality_ZeroExposure() {
        Map<String, Object> result = analysisService.analyzeClickQuality(0L, 0L);

        assertNotNull(result);
        assertEquals(BigDecimal.ZERO, result.get("clickRate"));
        assertEquals("no_data", result.get("quality"));
    }

    @Test
    @DisplayName("测试转化率计算 - 高转化率")
    void testAnalyzeConversionQuality_HighConversion() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(100L, 15L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.1500"), result.get("conversionRate"));
        assertEquals("excellent", result.get("quality"));
    }

    @Test
    @DisplayName("测试转化率计算 - 良好转化率")
    void testAnalyzeConversionQuality_GoodConversion() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(100L, 7L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0700"), result.get("conversionRate"));
        assertEquals("good", result.get("quality"));
    }

    @Test
    @DisplayName("测试转化率计算 - 一般转化率")
    void testAnalyzeConversionQuality_AverageConversion() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(100L, 3L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0300"), result.get("conversionRate"));
        assertEquals("average", result.get("quality"));
    }

    @Test
    @DisplayName("测试转化率计算 - 低转化率")
    void testAnalyzeConversionQuality_PoorConversion() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(100L, 1L);

        assertNotNull(result);
        assertEquals(new BigDecimal("0.0100"), result.get("conversionRate"));
        assertEquals("poor", result.get("quality"));
    }

    @Test
    @DisplayName("测试转化率计算 - 零点击")
    void testAnalyzeConversionQuality_ZeroClick() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(0L, 0L);

        assertNotNull(result);
        assertEquals(BigDecimal.ZERO, result.get("conversionRate"));
        assertEquals("no_data", result.get("quality"));
    }

    @Test
    @DisplayName("测试效果评分计算 - 高性能广告评分")
    void testPerformanceScore_HighPerformanceAd() {
        EffectQueryResponse highEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 800L, 100L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(highEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        BigDecimal performanceScore = (BigDecimal) analysis.get("performanceScore");
        assertNotNull(performanceScore);
        
        BigDecimal expectedClickRate = new BigDecimal("800")
                .divide(new BigDecimal("10000"), 4, RoundingMode.HALF_UP);
        BigDecimal expectedConversionRate = new BigDecimal("100")
                .divide(new BigDecimal("800"), 4, RoundingMode.HALF_UP);
        BigDecimal expectedScore = expectedClickRate.multiply(new BigDecimal("50"))
                .add(expectedConversionRate.multiply(new BigDecimal("50")));
        
        assertEquals(expectedScore.setScale(2, RoundingMode.HALF_UP), performanceScore);
    }

    @Test
    @DisplayName("测试效果评分计算 - 低性能广告评分")
    void testPerformanceScore_LowPerformanceAd() {
        EffectQueryResponse lowEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 50L, 1L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(lowEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        BigDecimal performanceScore = (BigDecimal) analysis.get("performanceScore");
        assertNotNull(performanceScore);
        assertTrue(performanceScore.compareTo(new BigDecimal("10")) < 0, "低性能广告评分应该较低");
    }

    @Test
    @DisplayName("测试效果状态分析 - 表现良好")
    void testStatusAnalysis_PerformingWell() {
        EffectQueryResponse goodEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 200L, 30L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(goodEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertEquals("performing_well", analysis.get("status"));
    }

    @Test
    @DisplayName("测试效果状态分析 - 低点击率")
    void testStatusAnalysis_LowCTR() {
        EffectQueryResponse lowCtrEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 50L, 10L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(lowCtrEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertEquals("low_ctr", analysis.get("status"));
    }

    @Test
    @DisplayName("测试效果状态分析 - 低转化率")
    void testStatusAnalysis_LowConversion() {
        EffectQueryResponse lowConvEffect = EffectQueryResponse.builder()
                .exposureCount(10000L)
                .clickCount(500L)
                .clickRate(new BigDecimal("0.05"))
                .conversionCount(3L)
                .conversionRate(new BigDecimal("0.006"))
                .build();
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(lowConvEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertEquals("low_conversion", analysis.get("status"));
    }

    @Test
    @DisplayName("测试优化建议生成 - 高点击率广告")
    void testRecommendations_HighCTR() {
        EffectQueryResponse highCtrEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 600L, 60L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(highCtrEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        @SuppressWarnings("unchecked")
        String[] recommendations = (String[]) analysis.get("recommendations");
        assertNotNull(recommendations);
        
        boolean hasIncreaseBudgetAdvice = false;
        for (String rec : recommendations) {
            if (rec.contains("增加预算") || rec.contains("increase")) {
                hasIncreaseBudgetAdvice = true;
                break;
            }
        }
        assertTrue(hasIncreaseBudgetAdvice, "高点击率广告应该有增加预算的建议");
    }

    @Test
    @DisplayName("测试优化建议生成 - 低点击率广告")
    void testRecommendations_LowCTR() {
        EffectQueryResponse lowCtrEffect = TestDataBuilder.buildEffectQueryResponse(10000L, 50L, 5L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(lowCtrEffect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        @SuppressWarnings("unchecked")
        String[] recommendations = (String[]) analysis.get("recommendations");
        assertNotNull(recommendations);
        
        boolean hasCreativeAdvice = false;
        for (String rec : recommendations) {
            if (rec.contains("创意") || rec.contains("creative") || rec.contains("点击率")) {
                hasCreativeAdvice = true;
                break;
            }
        }
        assertTrue(hasCreativeAdvice, "低点击率广告应该有创意优化建议");
    }

    @Test
    @DisplayName("测试多维度分析 - 时间范围参数")
    void testMultiDimensionalAnalysis_TimeRange() {
        LocalDate startDate = today.minusDays(30);
        LocalDate endDate = today;
        
        EffectQueryResponse effect = TestDataBuilder.buildEffectQueryResponse(50000L, 2500L, 250L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(effect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                startDate, 
                endDate
        );

        @SuppressWarnings("unchecked")
        Map<String, LocalDate> timeRange = (Map<String, LocalDate>) analysis.get("timeRange");
        assertNotNull(timeRange);
        assertEquals(startDate, timeRange.get("start"));
        assertEquals(endDate, timeRange.get("end"));
    }

    @Test
    @DisplayName("测试多维度分析 - 效果指标完整性")
    void testMultiDimensionalAnalysis_MetricsCompleteness() {
        EffectQueryResponse effect = TestDataBuilder.buildEffectQueryResponse(10000L, 500L, 50L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(effect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        Map<String, Object> analysis = analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        assertTrue(analysis.containsKey("adId"));
        assertTrue(analysis.containsKey("timeRange"));
        assertTrue(analysis.containsKey("exposureCount"));
        assertTrue(analysis.containsKey("clickCount"));
        assertTrue(analysis.containsKey("clickRate"));
        assertTrue(analysis.containsKey("conversionCount"));
        assertTrue(analysis.containsKey("conversionRate"));
        assertTrue(analysis.containsKey("performanceScore"));
        assertTrue(analysis.containsKey("recommendations"));
        assertTrue(analysis.containsKey("status"));
    }

    @Test
    @DisplayName("测试历史记录保存 - 分析后保存历史")
    void testHistorySaving_AnalysisHistory() {
        EffectQueryResponse effect = TestDataBuilder.buildEffectQueryResponse(10000L, 500L, 50L);
        
        when(statisticsService.queryEffects(any(EffectQueryRequest.class))).thenReturn(effect);
        when(adHistoryRepository.save(any())).thenReturn(null);

        analysisService.analyzeAdPerformance(
                testAdId, 
                today.minusDays(7), 
                today
        );

        verify(adHistoryRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("测试点击率计算精度 - 小数位数")
    void testClickRateCalculation_Precision() {
        Map<String, Object> result = analysisService.analyzeClickQuality(3L, 1L);

        BigDecimal clickRate = (BigDecimal) result.get("clickRate");
        assertEquals(4, clickRate.scale(), "点击率应该保留4位小数");
        assertEquals(new BigDecimal("0.3333"), clickRate);
    }

    @Test
    @DisplayName("测试转化率计算精度 - 小数位数")
    void testConversionRateCalculation_Precision() {
        Map<String, Object> result = analysisService.analyzeConversionQuality(7L, 2L);

        BigDecimal conversionRate = (BigDecimal) result.get("conversionRate");
        assertEquals(4, conversionRate.scale(), "转化率应该保留4位小数");
        assertEquals(new BigDecimal("0.2857"), conversionRate);
    }
}
