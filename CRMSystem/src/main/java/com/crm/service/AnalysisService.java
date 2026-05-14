package com.crm.service;

import com.crm.entity.Statistics;
import com.crm.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class AnalysisService {

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    public Map<String, Object> getOverviewStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        Long totalCustomers = customerRepository.count();
        Long currentMonthCustomers = customerRepository.countCurrentMonthCustomers();
        Long dealCustomers = customerRepository.countDealCustomers();
        Long totalFollows = followRepository.count();
        Long currentMonthFollows = followRepository.countCurrentMonthFollows();
        Long totalOpportunities = opportunityRepository.count();
        Long currentMonthOpportunities = opportunityRepository.countCurrentMonthOpportunities();
        Long successOpportunities = opportunityRepository.countSuccessOpportunities();
        Long failedOpportunities = opportunityRepository.countFailedOpportunities();
        Double currentMonthDealAmount = opportunityRepository.sumCurrentMonthDealAmount();

        stats.put("totalCustomers", totalCustomers);
        stats.put("currentMonthCustomers", currentMonthCustomers);
        stats.put("dealCustomers", dealCustomers);
        stats.put("totalFollows", totalFollows);
        stats.put("currentMonthFollows", currentMonthFollows);
        stats.put("totalOpportunities", totalOpportunities);
        stats.put("currentMonthOpportunities", currentMonthOpportunities);
        stats.put("successOpportunities", successOpportunities);
        stats.put("failedOpportunities", failedOpportunities);
        stats.put("currentMonthDealAmount", currentMonthDealAmount != null ? currentMonthDealAmount : 0.0);

        return stats;
    }

    public Statistics getMonthlyStatistics(String month) {
        return statisticsRepository.findByStatMonth(month).orElse(null);
    }

    public void incrementFollowCount() {
        updateMonthlyStats("follow");
    }

    public void incrementOpportunityCount() {
        updateMonthlyStats("opportunity");
    }

    public void addDealAmount(Double amount) {
        String currentMonth = getCurrentMonth();
        Statistics stats = statisticsRepository.findByStatMonth(currentMonth)
                .orElseGet(() -> createNewStatistics(currentMonth));
        stats.setDealAmount(stats.getDealAmount() + amount);
        stats.setSuccessCount(stats.getSuccessCount() + 1);
        statisticsRepository.save(stats);
    }

    public void incrementFailCount() {
        String currentMonth = getCurrentMonth();
        Statistics stats = statisticsRepository.findByStatMonth(currentMonth)
                .orElseGet(() -> createNewStatistics(currentMonth));
        stats.setFailCount(stats.getFailCount() + 1);
        statisticsRepository.save(stats);
    }

    public void incrementCustomerCount() {
        updateMonthlyStats("customer");
    }

    private void updateMonthlyStats(String type) {
        String currentMonth = getCurrentMonth();
        Statistics stats = statisticsRepository.findByStatMonth(currentMonth)
                .orElseGet(() -> createNewStatistics(currentMonth));
        
        switch (type) {
            case "customer":
                stats.setCustomerCount(stats.getCustomerCount() + 1);
                break;
            case "follow":
                stats.setFollowCount(stats.getFollowCount() + 1);
                break;
            case "opportunity":
                stats.setOpportunityCount(stats.getOpportunityCount() + 1);
                break;
        }
        statisticsRepository.save(stats);
    }

    private Statistics createNewStatistics(String month) {
        Statistics stats = new Statistics();
        stats.setStatId(com.crm.common.IdGenerator.generateStatId());
        stats.setStatMonth(month);
        stats.setCustomerCount(0);
        stats.setFollowCount(0);
        stats.setOpportunityCount(0);
        stats.setDealAmount(0.0);
        stats.setSuccessCount(0);
        stats.setFailCount(0);
        return stats;
    }

    private String getCurrentMonth() {
        LocalDate now = LocalDate.now();
        return String.format("%04d-%02d", now.getYear(), now.getMonthValue());
    }
}
