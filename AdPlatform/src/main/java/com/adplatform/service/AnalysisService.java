package com.adplatform.service;

import com.adplatform.dto.EffectQueryRequest;
import com.adplatform.dto.EffectQueryResponse;
import com.adplatform.entity.AdHistory;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);
    
    private final StatisticsService statisticsService;
    private final AdHistoryRepository adHistoryRepository;

    public AnalysisService(StatisticsService statisticsService,
                          AdHistoryRepository adHistoryRepository) {
        this.statisticsService = statisticsService;
        this.adHistoryRepository = adHistoryRepository;
    }

    public Map<String, Object> analyzeAdPerformance(String adId, LocalDate startDate, LocalDate endDate) {
        EffectQueryRequest request = EffectQueryRequest.builder()
                .adId(adId)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        
        EffectQueryResponse effects = statisticsService.queryEffects(request);
        
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("adId", adId);
        analysis.put("timeRange", Map.of("start", startDate, "end", endDate));
        analysis.put("exposureCount", effects.getExposureCount());
        analysis.put("clickCount", effects.getClickCount());
        analysis.put("clickRate", effects.getClickRate());
        analysis.put("conversionCount", effects.getConversionCount());
        analysis.put("conversionRate", effects.getConversionRate());
        
        BigDecimal performanceScore = calculatePerformanceScore(effects);
        analysis.put("performanceScore", performanceScore);
        
        analysis.put("recommendations", generateRecommendations(effects));
        analysis.put("status", analyzeStatus(effects));
        
        recordAnalysisHistory(adId, analysis);
        logger.info("广告分析完成: adId={}, 效果评分={}", adId, performanceScore);
        
        return analysis;
    }

    public Map<String, Object> analyzeClickQuality(Long exposureCount, Long clickCount) {
        Map<String, Object> result = new HashMap<>();
        
        if (exposureCount == null || exposureCount == 0) {
            result.put("clickRate", BigDecimal.ZERO);
            result.put("quality", "no_data");
            return result;
        }
        
        BigDecimal clickRate = new BigDecimal(clickCount)
                .divide(new BigDecimal(exposureCount), 4, RoundingMode.HALF_UP);
        
        result.put("clickRate", clickRate);
        
        String quality;
        if (clickRate.compareTo(new BigDecimal("0.05")) >= 0) {
            quality = "excellent";
        } else if (clickRate.compareTo(new BigDecimal("0.02")) >= 0) {
            quality = "good";
        } else if (clickRate.compareTo(new BigDecimal("0.01")) >= 0) {
            quality = "average";
        } else {
            quality = "poor";
        }
        result.put("quality", quality);
        
        return result;
    }

    public Map<String, Object> analyzeConversionQuality(Long clickCount, Long conversionCount) {
        Map<String, Object> result = new HashMap<>();
        
        if (clickCount == null || clickCount == 0) {
            result.put("conversionRate", BigDecimal.ZERO);
            result.put("quality", "no_data");
            return result;
        }
        
        BigDecimal conversionRate = new BigDecimal(conversionCount)
                .divide(new BigDecimal(clickCount), 4, RoundingMode.HALF_UP);
        
        result.put("conversionRate", conversionRate);
        
        String quality;
        if (conversionRate.compareTo(new BigDecimal("0.1")) >= 0) {
            quality = "excellent";
        } else if (conversionRate.compareTo(new BigDecimal("0.05")) >= 0) {
            quality = "good";
        } else if (conversionRate.compareTo(new BigDecimal("0.02")) >= 0) {
            quality = "average";
        } else {
            quality = "poor";
        }
        result.put("quality", quality);
        
        return result;
    }

    private BigDecimal calculatePerformanceScore(EffectQueryResponse effects) {
        BigDecimal clickRateScore = effects.getClickRate().multiply(new BigDecimal("50"));
        BigDecimal conversionRateScore = effects.getConversionRate().multiply(new BigDecimal("50"));
        return clickRateScore.add(conversionRateScore).setScale(2, RoundingMode.HALF_UP);
    }

    private String analyzeStatus(EffectQueryResponse effects) {
        if (effects.getExposureCount() == 0) {
            return "no_impressions";
        }
        
        BigDecimal clickRate = effects.getClickRate();
        if (clickRate.compareTo(new BigDecimal("0.01")) < 0) {
            return "low_ctr";
        }
        
        if (effects.getClickCount() > 0 && effects.getConversionRate().compareTo(new BigDecimal("0.01")) < 0) {
            return "low_conversion";
        }
        
        return "performing_well";
    }

    private String[] generateRecommendations(EffectQueryResponse effects) {
        java.util.List<String> recommendations = new java.util.ArrayList<>();
        
        if (effects.getExposureCount() == 0) {
            recommendations.add("广告暂无曝光，请检查投放配置");
        } else if (effects.getClickRate().compareTo(new BigDecimal("0.01")) < 0) {
            recommendations.add("点击率偏低，建议优化广告创意和定向条件");
        }
        
        if (effects.getClickCount() > 0 && effects.getConversionRate().compareTo(new BigDecimal("0.01")) < 0) {
            recommendations.add("转化率偏低，建议优化落地页和转化路径");
        }
        
        if (effects.getClickRate().compareTo(new BigDecimal("0.05")) >= 0) {
            recommendations.add("点击率表现优秀，可考虑增加预算");
        }
        
        if (effects.getConversionRate().compareTo(new BigDecimal("0.1")) >= 0) {
            recommendations.add("转化率表现优秀，建议优化投放规模");
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("广告效果良好，继续保持当前策略");
        }
        
        return recommendations.toArray(new String[0]);
    }

    @Transactional
    private void recordAnalysisHistory(String adId, Map<String, Object> analysisData) {
        Map<String, Object> historyData = new HashMap<>(analysisData);
        
        AdHistory history = AdHistory.builder()
                .historyId(IdGenerator.generateId("history"))
                .adId(adId)
                .historyType("AD_ANALYZED")
                .historyData(historyData)
                .build();
        adHistoryRepository.save(history);
    }
}
