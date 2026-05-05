package com.paygateway.service;

import com.paygateway.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {
    
    private final PaymentOrderRepository paymentOrderRepository;
    
    public Map<String, Object> getDailyStatistics(LocalDate date) {
        LocalDateTime startTime = date.atStartOfDay();
        LocalDateTime endTime = date.atTime(LocalTime.MAX);
        
        return getStatistics(startTime, endTime);
    }
    
    public Map<String, Object> getStatistics(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> result = new HashMap<>();
        
        Long totalOrders = paymentOrderRepository.countByCreatedAtBetween(startTime, endTime);
        BigDecimal totalAmount = paymentOrderRepository.sumAmountByCreatedAtBetweenAndStatus(startTime, endTime, "paid");
        
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
        
        result.put("totalOrders", totalOrders);
        result.put("totalAmount", totalAmount);
        
        List<Object[]> channelStats = paymentOrderRepository.countAndSumByChannelBetween(startTime, endTime);
        List<Map<String, Object>> channelStatistics = new ArrayList<>();
        
        for (Object[] row : channelStats) {
            Map<String, Object> channelMap = new HashMap<>();
            channelMap.put("channel", row[0]);
            channelMap.put("orderCount", row[1]);
            channelMap.put("totalAmount", row[2] != null ? row[2] : BigDecimal.ZERO);
            channelStatistics.add(channelMap);
        }
        
        result.put("channelStatistics", channelStatistics);
        
        log.info("统计查询结果：startTime={}, endTime={}, totalOrders={}, totalAmount={}", 
                startTime, endTime, totalOrders, totalAmount);
        
        return result;
    }
    
    public Map<String, Object> getRangeStatistics(LocalDate startDate, LocalDate endDate) {
        LocalDateTime startTime = startDate.atStartOfDay();
        LocalDateTime endTime = endDate.atTime(LocalTime.MAX);
        
        return getStatistics(startTime, endTime);
    }
}
