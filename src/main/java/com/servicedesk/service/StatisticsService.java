package com.servicedesk.service;

import com.servicedesk.entity.Statistic;
import com.servicedesk.repository.SatisfactionRepository;
import com.servicedesk.repository.StatisticRepository;
import com.servicedesk.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final StatisticRepository statisticRepository;
    private final TicketRepository ticketRepository;
    private final SatisfactionRepository satisfactionRepository;

    private final AtomicInteger totalTicketsToday = new AtomicInteger(0);
    private final AtomicInteger resolvedTicketsToday = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicInteger responseCount = new AtomicInteger(0);
    private final AtomicLong totalResolutionTime = new AtomicLong(0);
    private final AtomicInteger resolutionCount = new AtomicInteger(0);

    @Transactional
    public void incrementTotalTickets() {
        totalTicketsToday.incrementAndGet();
        log.debug("工单总数加1，当前: {}", totalTicketsToday.get());
    }

    @Transactional
    public void incrementResolvedTickets() {
        resolvedTicketsToday.incrementAndGet();
        log.debug("已解决工单加1，当前: {}", resolvedTicketsToday.get());
    }

    @Transactional
    public void updateResponseTimeStats(long responseTime) {
        totalResponseTime.addAndGet(responseTime);
        responseCount.incrementAndGet();
        log.debug("响应时间统计更新: 新增{}秒", responseTime);
    }

    @Transactional
    public void updateResolutionTimeStats(long resolutionTime) {
        totalResolutionTime.addAndGet(resolutionTime);
        resolutionCount.incrementAndGet();
        log.debug("解决时间统计更新: 新增{}秒", resolutionTime);
    }

    @Transactional
    public void updateSatisfactionRate() {
        log.debug("满意度统计更新触发");
    }

    @Transactional
    public Statistic getTodayStatistics() {
        LocalDate today = LocalDate.now();
        Optional<Statistic> existingStat = statisticRepository.findByStatDate(today);

        if (existingStat.isPresent()) {
            Statistic stat = existingStat.get();
            updateStatisticFromLiveData(stat);
            return statisticRepository.save(stat);
        }

        Statistic newStat = new Statistic();
        newStat.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        newStat.setStatDate(today);
        updateStatisticFromLiveData(newStat);
        return statisticRepository.save(newStat);
    }

    private void updateStatisticFromLiveData(Statistic statistic) {
        statistic.setTotalTickets(totalTicketsToday.get());
        statistic.setResolvedTickets(resolvedTicketsToday.get());

        if (responseCount.get() > 0) {
            statistic.setAvgResponseTime(totalResponseTime.get() / responseCount.get());
        }

        if (resolutionCount.get() > 0) {
            statistic.setAvgResolutionTime(totalResolutionTime.get() / resolutionCount.get());
        }

        Double avgSatisfaction = satisfactionRepository.findAvgSatisfactionScore();
        if (avgSatisfaction != null) {
            statistic.setSatisfactionRate(avgSatisfaction * 20);
        }
    }

    @Transactional
    public Statistic getOverallStatistics() {
        long totalTicketsDb = ticketRepository.count();
        long resolvedTicketsDb = ticketRepository.countResolvedTickets();
        Long avgResponse = ticketRepository.findAvgResponseTime();
        Long avgResolution = ticketRepository.findAvgResolutionTime();
        Double satisfactionRate = satisfactionRepository.findAvgSatisfactionScore();

        Statistic statistic = new Statistic();
        statistic.setStatId("stat_overall");
        statistic.setStatDate(LocalDate.now());
        statistic.setTotalTickets((int) totalTicketsDb);
        statistic.setResolvedTickets((int) resolvedTicketsDb);
        statistic.setAvgResponseTime(avgResponse);
        statistic.setAvgResolutionTime(avgResolution);
        statistic.setSatisfactionRate(satisfactionRate != null ? satisfactionRate * 20 : 0.0);

        return statistic;
    }

    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void saveDailyStatistics() {
        LocalDate today = LocalDate.now();
        
        if (!statisticRepository.existsByStatDate(today)) {
            Statistic stat = new Statistic();
            stat.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
            stat.setStatDate(today);
            updateStatisticFromLiveData(stat);
            statisticRepository.save(stat);
            log.info("每日统计已保存: {}", today);
        }

        totalTicketsToday.set(0);
        resolvedTicketsToday.set(0);
        totalResponseTime.set(0);
        responseCount.set(0);
        totalResolutionTime.set(0);
        resolutionCount.set(0);
    }
}
