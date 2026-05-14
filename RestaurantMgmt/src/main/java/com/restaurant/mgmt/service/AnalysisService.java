package com.restaurant.mgmt.service;

import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.model.OrderItem;
import com.restaurant.mgmt.model.SalesStat;
import com.restaurant.mgmt.repository.SalesStatRepository;
import com.restaurant.mgmt.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AnalysisService {

    @Autowired
    private SalesStatRepository salesStatRepository;

    @Transactional
    public void updateSalesStats(Order order) {
        LocalDate statDate = LocalDate.from(order.getCreatedAt());
        SalesStat stat = salesStatRepository.findByStatDate(statDate)
                .orElseGet(() -> {
                    SalesStat newStat = new SalesStat();
                    newStat.setStatId(IdGenerator.generateStatId());
                    newStat.setStatDate(statDate);
                    newStat.setOrderCount(0);
                    newStat.setTotalAmount(0);
                    newStat.setCancelledOrderCount(0);
                    newStat.setDishSales(new HashMap<>());
                    return newStat;
                });

        stat.setOrderCount(stat.getOrderCount() + 1);
        stat.setTotalAmount(stat.getTotalAmount() + order.getOrderAmount());
        
        for (OrderItem item : order.getOrderItems()) {
            stat.addDishSale(item.getDishId(), item.getQuantity());
        }
        
        if (stat.getOrderCount() > 0) {
            stat.setAvgOrderAmount(stat.getTotalAmount() / stat.getOrderCount());
        }
        stat.setUpdatedAt(LocalDateTime.now());
        
        salesStatRepository.save(stat);
    }

    @Transactional
    public void recordCancelledOrder(Order order) {
        LocalDate statDate = LocalDate.from(order.getCreatedAt());
        SalesStat stat = salesStatRepository.findByStatDate(statDate).orElse(null);
        
        if (stat != null) {
            stat.setCancelledOrderCount(stat.getCancelledOrderCount() + 1);
            stat.setUpdatedAt(LocalDateTime.now());
            salesStatRepository.save(stat);
        }
    }

    public SalesStat getTodayStats() {
        LocalDate today = LocalDate.now();
        return salesStatRepository.findByStatDate(today)
                .orElseThrow(() -> new BusinessException("今日暂无统计数据"));
    }

    public SalesStat getStatsByDate(LocalDate date) {
        return salesStatRepository.findByStatDate(date)
                .orElseThrow(() -> new BusinessException("指定日期暂无统计数据"));
    }

    public List<SalesStat> getStatsByDateRange(LocalDate startDate, LocalDate endDate) {
        return salesStatRepository.findByStatDateBetween(startDate, endDate);
    }

    public Map<String, Object> getSummaryStats(LocalDate startDate, LocalDate endDate) {
        List<SalesStat> stats = getStatsByDateRange(startDate, endDate);
        
        Map<String, Object> summary = new HashMap<>();
        int totalOrders = 0;
        double totalRevenue = 0;
        int cancelledOrders = 0;
        Map<String, Integer> dishSales = new HashMap<>();
        
        for (SalesStat stat : stats) {
            totalOrders += stat.getOrderCount();
            totalRevenue += stat.getTotalAmount();
            cancelledOrders += stat.getCancelledOrderCount();
            
            for (Map.Entry<String, Integer> entry : stat.getDishSales().entrySet()) {
                dishSales.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        
        summary.put("totalOrders", totalOrders);
        summary.put("totalRevenue", totalRevenue);
        summary.put("cancelledOrders", cancelledOrders);
        summary.put("avgOrderAmount", totalOrders > 0 ? totalRevenue / totalOrders : 0);
        summary.put("cancellationRate", totalOrders > 0 ? (double) cancelledOrders / totalOrders : 0);
        summary.put("dishSales", dishSales);
        summary.put("dayCount", stats.size());
        
        return summary;
    }

    public List<Map<String, Object>> getTopDishes(LocalDate startDate, LocalDate endDate, int limit) {
        List<SalesStat> stats = getStatsByDateRange(startDate, endDate);
        Map<String, Integer> dishSales = new HashMap<>();
        
        for (SalesStat stat : stats) {
            for (Map.Entry<String, Integer> entry : stat.getDishSales().entrySet()) {
                dishSales.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(dishSales.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            Map<String, Object> item = new HashMap<>();
            item.put("dishId", entry.getKey());
            item.put("salesCount", entry.getValue());
            item.put("rank", i + 1);
            result.add(item);
        }
        
        return result;
    }

    public Map<String, Object> getDailyTrend(LocalDate startDate, LocalDate endDate) {
        List<SalesStat> stats = getStatsByDateRange(startDate, endDate);
        Map<String, Object> trend = new HashMap<>();
        
        List<String> dates = new ArrayList<>();
        List<Integer> orderCounts = new ArrayList<>();
        List<Double> revenues = new ArrayList<>();
        
        for (SalesStat stat : stats) {
            dates.add(stat.getStatDate().toString());
            orderCounts.add(stat.getOrderCount());
            revenues.add(stat.getTotalAmount());
        }
        
        trend.put("dates", dates);
        trend.put("orderCounts", orderCounts);
        trend.put("revenues", revenues);
        
        return trend;
    }
}
