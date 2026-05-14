package com.adplatform.service;

import com.adplatform.dto.EffectEvent;
import com.adplatform.queue.EffectEventQueue;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class EffectService {
    private static final Logger logger = LoggerFactory.getLogger(EffectService.class);
    
    private final EffectEventQueue effectEventQueue;
    private final StatisticsService statisticsService;
    private final BudgetService budgetService;
    private final AnalysisService analysisService;

    public EffectService(EffectEventQueue effectEventQueue,
                        StatisticsService statisticsService,
                        BudgetService budgetService,
                        AnalysisService analysisService) {
        this.effectEventQueue = effectEventQueue;
        this.statisticsService = statisticsService;
        this.budgetService = budgetService;
        this.analysisService = analysisService;
    }

    @PostConstruct
    public void init() {
        effectEventQueue.processEvents(this::processEffectEvent);
        logger.info("效果事件处理器已启动");
    }

    public void submitEffectEvent(EffectEvent event) {
        effectEventQueue.offer(event);
        logger.debug("效果事件已提交: adId={}, eventType={}", event.getAdId(), event.getEventType());
    }

    private void processEffectEvent(EffectEvent event) {
        try {
            if (!budgetService.isAdRunning(event.getAdId())) {
                logger.warn("广告未在投放中，忽略效果事件: adId={}", event.getAdId());
                return;
            }

            if ("exposure".equals(event.getEventType())) {
                processExposure(event);
            } else if ("click".equals(event.getEventType())) {
                processClick(event);
            } else if ("conversion".equals(event.getEventType())) {
                processConversion(event);
            }

        } catch (Exception e) {
            logger.error("处理效果事件失败: adId={}", event.getAdId(), e);
        }
    }

    private void processExposure(EffectEvent event) {
        statisticsService.recordExposure(event.getAdId(), event.getPosition());
        
        if (event.getCostAmount() != null && event.getCostAmount().compareTo(BigDecimal.ZERO) > 0) {
            boolean deducted = budgetService.consumeBudget(event.getAdId(), "exposure", event.getCostAmount());
            if (!deducted) {
                logger.warn("曝光预算扣减失败: adId={}", event.getAdId());
            }
        }
        
        logger.debug("曝光事件处理完成: adId={}", event.getAdId());
    }

    private void processClick(EffectEvent event) {
        statisticsService.recordClick(event.getAdId(), event.getUserInfo());
        
        if (event.getCostAmount() != null && event.getCostAmount().compareTo(BigDecimal.ZERO) > 0) {
            boolean deducted = budgetService.consumeBudget(event.getAdId(), "click", event.getCostAmount());
            if (!deducted) {
                logger.warn("点击预算扣减失败: adId={}", event.getAdId());
            }
        }
        
        logger.debug("点击事件处理完成: adId={}", event.getAdId());
    }

    private void processConversion(EffectEvent event) {
        statisticsService.recordConversion(event.getAdId());
        
        if (event.getCostAmount() != null && event.getCostAmount().compareTo(BigDecimal.ZERO) > 0) {
            boolean deducted = budgetService.consumeBudget(event.getAdId(), "conversion", event.getCostAmount());
            if (!deducted) {
                logger.warn("转化预算扣减失败: adId={}", event.getAdId());
            }
        }
        
        logger.debug("转化事件处理完成: adId={}", event.getAdId());
    }
}
