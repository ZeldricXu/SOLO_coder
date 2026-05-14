package com.survey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncStatisticsService {

    private final StatisticsService statisticsService;
    private final Set<String> pendingSurveys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Async("statTaskExecutor")
    public void updateStatisticsAsync(String surveyId) {
        log.info("开始异步统计计算，问卷ID: {}", surveyId);

        try {
            pendingSurveys.add(surveyId);
            statisticsService.updateStatistics(surveyId);
            log.info("异步统计计算完成，问卷ID: {}", surveyId);
        } catch (Exception e) {
            log.error("异步统计计算失败，问卷ID: {}", surveyId, e);
        } finally {
            pendingSurveys.remove(surveyId);
        }
    }

    @Async("statTaskExecutor")
    public void triggerStatUpdate(String surveyId) {
        if (pendingSurveys.contains(surveyId)) {
            log.debug("问卷 {} 统计正在进行中，跳过重复请求", surveyId);
            return;
        }
        updateStatisticsAsync(surveyId);
    }

    public boolean isStatisticsProcessing(String surveyId) {
        return pendingSurveys.contains(surveyId);
    }

    public int getPendingStatisticsCount() {
        return pendingSurveys.size();
    }
}
