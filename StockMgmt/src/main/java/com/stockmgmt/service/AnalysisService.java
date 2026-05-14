package com.stockmgmt.service;

import com.stockmgmt.entity.Stock;
import com.stockmgmt.enums.OperationType;
import com.stockmgmt.repository.StockRepository;
import com.stockmgmt.repository.StockWarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StockWarningRepository warningRepository;

    public Map<String, Object> getOverviewStatistics() {
        logger.info("获取库存概览统计");

        Map<String, Object> result = new LinkedHashMap<>();

        List<Stock> allStocks = stockRepository.findAll();

        int totalItems = allStocks.size();
        int totalQuantity = allStocks.stream().mapToInt(Stock::getCurrentQuantity).sum();
        int totalAvailable = allStocks.stream().mapToInt(Stock::getAvailableQuantity).sum();
        int totalLocked = allStocks.stream().mapToInt(Stock::getLockedQuantity).sum();

        BigDecimal totalValue = BigDecimal.ZERO;
        for (Stock stock : allStocks) {
            if (stock.getCostPrice() != null) {
                totalValue = totalValue.add(stock.getCostPrice().multiply(BigDecimal.valueOf(stock.getCurrentQuantity())));
            }
        }

        result.put("totalItems", totalItems);
        result.put("totalQuantity", totalQuantity);
        result.put("totalAvailable", totalAvailable);
        result.put("totalLocked", totalLocked);
        result.put("totalValue", totalValue);

        long activeWarnings = warningRepository.countByStatus(com.stockmgmt.enums.WarningStatus.ACTIVE);
        result.put("activeWarnings", activeWarnings);

        int lowStockCount = stockRepository.findLowStock().size();
        int overstockCount = stockRepository.findOverstock().size();
        result.put("lowStockCount", lowStockCount);
        result.put("overstockCount", overstockCount);

        logger.info("库存概览统计完成");
        return result;
    }

    public Map<String, Object> getTurnoverAnalysis(int days) {
        logger.info("获取库存周转分析，天数: {}", days);

        Map<String, Object> result = new LinkedHashMap<>();

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(days);

        int totalInbound = historyService.getTotalInbound(startTime, endTime);
        int totalOutbound = historyService.getTotalOutbound(startTime, endTime);

        long inboundCount = historyService.getOperationCount(OperationType.INBOUND, startTime, endTime);
        long outboundCount = historyService.getOperationCount(OperationType.OUTBOUND, startTime, endTime);

        List<Stock> stocks = stockRepository.findAll();
        int averageStock = stocks.stream().mapToInt(Stock::getCurrentQuantity).sum();

        double turnoverRate = averageStock > 0 ? (double) totalOutbound / averageStock : 0;
        double turnoverDays = turnoverRate > 0 ? days / turnoverRate : 0;

        result.put("periodDays", days);
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("totalInbound", totalInbound);
        result.put("totalOutbound", totalOutbound);
        result.put("inboundCount", inboundCount);
        result.put("outboundCount", outboundCount);
        result.put("averageStock", averageStock);
        result.put("turnoverRate", String.format("%.4f", turnoverRate));
        result.put("turnoverDays", String.format("%.2f", turnoverDays));
        result.put("netChange", totalInbound - totalOutbound);

        logger.info("库存周转分析完成");
        return result;
    }

    public Map<String, Object> getCostAnalysis() {
        logger.info("获取库存成本分析");

        Map<String, Object> result = new LinkedHashMap<>();

        List<Stock> stocks = stockRepository.findAll();

        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalAvailableValue = BigDecimal.ZERO;
        BigDecimal totalLockedValue = BigDecimal.ZERO;

        Map<String, BigDecimal> warehouseValue = new LinkedHashMap<>();

        for (Stock stock : stocks) {
            if (stock.getCostPrice() != null) {
                BigDecimal currentValue = stock.getCostPrice().multiply(BigDecimal.valueOf(stock.getCurrentQuantity()));
                totalValue = totalValue.add(currentValue);

                BigDecimal availableValue = stock.getCostPrice().multiply(BigDecimal.valueOf(stock.getAvailableQuantity()));
                totalAvailableValue = totalAvailableValue.add(availableValue);

                BigDecimal lockedValue = stock.getCostPrice().multiply(BigDecimal.valueOf(stock.getLockedQuantity()));
                totalLockedValue = totalLockedValue.add(lockedValue);

                String warehouseId = stock.getWarehouseId() != null ? stock.getWarehouseId() : "unknown";
                warehouseValue.merge(warehouseId, currentValue, BigDecimal::add);
            }
        }

        result.put("totalValue", totalValue);
        result.put("totalAvailableValue", totalAvailableValue);
        result.put("totalLockedValue", totalLockedValue);
        result.put("warehouseValue", warehouseValue);

        int totalItems = stocks.size();
        BigDecimal avgValue = totalItems > 0 ? totalValue.divide(BigDecimal.valueOf(totalItems), 2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO;
        result.put("averageValuePerItem", avgValue);

        logger.info("库存成本分析完成");
        return result;
    }

    public List<Map<String, Object>> getTopLowStock(int limit) {
        logger.info("获取库存不足TOP，数量: {}", limit);

        List<Stock> lowStocks = stockRepository.findLowStock();
        lowStocks.sort((s1, s2) -> Integer.compare(s1.getCurrentQuantity(), s2.getCurrentQuantity()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, lowStocks.size()); i++) {
            Stock stock = lowStocks.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stockId", stock.getStockId());
            item.put("productId", stock.getProductId());
            item.put("productName", stock.getProductName());
            item.put("currentQuantity", stock.getCurrentQuantity());
            item.put("availableQuantity", stock.getAvailableQuantity());
            item.put("warningThreshold", stock.getWarningThreshold());
            item.put("gap", stock.getWarningThreshold() - stock.getCurrentQuantity());
            result.add(item);
        }

        logger.info("获取库存不足TOP完成");
        return result;
    }

    public List<Map<String, Object>> getTopOverstock(int limit) {
        logger.info("获取库存积压TOP，数量: {}", limit);

        List<Stock> overstocks = stockRepository.findOverstock();
        overstocks.sort((s1, s2) -> Integer.compare(s2.getCurrentQuantity(), s1.getCurrentQuantity()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, overstocks.size()); i++) {
            Stock stock = overstocks.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stockId", stock.getStockId());
            item.put("productId", stock.getProductId());
            item.put("productName", stock.getProductName());
            item.put("currentQuantity", stock.getCurrentQuantity());
            item.put("overstockThreshold", stock.getOverstockThreshold());
            item.put("excess", stock.getCurrentQuantity() - stock.getOverstockThreshold());
            result.add(item);
        }

        logger.info("获取库存积压TOP完成");
        return result;
    }

    public Map<String, Object> getWarehouseStatistics(String warehouseId) {
        logger.info("获取仓库统计，仓库ID: {}", warehouseId);

        Map<String, Object> result = new LinkedHashMap<>();

        List<Stock> stocks = stockRepository.findByWarehouseId(warehouseId);

        int totalItems = stocks.size();
        int totalQuantity = stocks.stream().mapToInt(Stock::getCurrentQuantity).sum();
        int totalAvailable = stocks.stream().mapToInt(Stock::getAvailableQuantity).sum();
        int totalLocked = stocks.stream().mapToInt(Stock::getLockedQuantity).sum();

        BigDecimal totalValue = BigDecimal.ZERO;
        for (Stock stock : stocks) {
            if (stock.getCostPrice() != null) {
                totalValue = totalValue.add(stock.getCostPrice().multiply(BigDecimal.valueOf(stock.getCurrentQuantity())));
            }
        }

        result.put("warehouseId", warehouseId);
        result.put("totalItems", totalItems);
        result.put("totalQuantity", totalQuantity);
        result.put("totalAvailable", totalAvailable);
        result.put("totalLocked", totalLocked);
        result.put("totalValue", totalValue);

        long lowStockCount = stocks.stream().filter(s -> s.getCurrentQuantity() <= s.getWarningThreshold()).count();
        long overstockCount = stocks.stream().filter(s -> s.getCurrentQuantity() >= s.getOverstockThreshold()).count();
        result.put("lowStockCount", lowStockCount);
        result.put("overstockCount", overstockCount);

        logger.info("获取仓库统计完成");
        return result;
    }

    public Map<String, Object> getDailyTrend(int days) {
        logger.info("获取每日趋势，天数: {}", days);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> dailyData = new ArrayList<>();

        LocalDateTime endTime = LocalDateTime.now();

        for (int i = days - 1; i >= 0; i--) {
            LocalDateTime dayStart = endTime.minusDays(i).withHour(0).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime dayEnd = dayStart.plusDays(1);

            int inbound = historyService.getTotalInbound(dayStart, dayEnd);
            int outbound = historyService.getTotalOutbound(dayStart, dayEnd);

            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", dayStart.toLocalDate().toString());
            dayData.put("inbound", inbound);
            dayData.put("outbound", outbound);
            dayData.put("net", inbound - outbound);
            dailyData.add(dayData);
        }

        result.put("periodDays", days);
        result.put("dailyData", dailyData);

        logger.info("获取每日趋势完成");
        return result;
    }
}
