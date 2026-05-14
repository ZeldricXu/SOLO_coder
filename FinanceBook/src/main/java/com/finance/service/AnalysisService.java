package com.finance.service;

import com.finance.entity.FinanceStat;
import com.finance.repository.FinanceStatRepository;
import com.finance.repository.RecordRepository;
import com.finance.util.IdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final FinanceStatRepository financeStatRepository;
    private final RecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void updateAnalysis(String accountId, String recordType, BigDecimal amount,
                                String category, LocalDateTime time) {
        String statMonth = YearMonth.from(time).toString();

        Optional<FinanceStat> existingStat = financeStatRepository
                .findByAccountIdAndStatMonth(accountId, statMonth);

        FinanceStat stat;
        LocalDateTime now = LocalDateTime.now();

        if (existingStat.isPresent()) {
            stat = existingStat.get();
            stat.setRecordCount(stat.getRecordCount() + 1);

            if ("income".equals(recordType)) {
                stat.setIncomeTotal(stat.getIncomeTotal().add(amount));
            } else {
                stat.setExpenseTotal(stat.getExpenseTotal().add(amount));
            }

            Map<String, BigDecimal> categoryStat = parseCategoryStat(stat.getCategoryStat());
            BigDecimal currentAmount = categoryStat.getOrDefault(category, BigDecimal.ZERO);
            categoryStat.put(category, currentAmount.add(amount));
            stat.setCategoryStat(serializeCategoryStat(categoryStat));
        } else {
            Map<String, BigDecimal> categoryStat = new HashMap<>();
            categoryStat.put(category, amount);

            stat = FinanceStat.builder()
                    .statId(IdGenerator.generateStatId())
                    .accountId(accountId)
                    .statMonth(statMonth)
                    .recordCount(1L)
                    .incomeTotal("income".equals(recordType) ? amount : BigDecimal.ZERO)
                    .expenseTotal("expense".equals(recordType) ? amount : BigDecimal.ZERO)
                    .categoryStat(serializeCategoryStat(categoryStat))
                    .build();
        }

        financeStatRepository.save(stat);
        log.debug("更新分析数据: accountId={}, month={}, count={}", accountId, statMonth, stat.getRecordCount());
    }

    @Transactional
    public void updateBudgetAnalysis(String accountId, String category, BigDecimal budgetAmount) {
        log.debug("更新预算分析: accountId={}, category={}, amount={}", accountId, category, budgetAmount);
    }

    @Transactional(readOnly = true)
    public FinanceStat getStatById(String statId) {
        return financeStatRepository.findById(statId)
                .orElseThrow(() -> new RuntimeException("统计数据不存在: " + statId));
    }

    @Transactional(readOnly = true)
    public List<FinanceStat> getStatsByAccount(String accountId) {
        return financeStatRepository.findByAccountIdOrderByStatMonthDesc(accountId);
    }

    @Transactional(readOnly = true)
    public Optional<FinanceStat> getStatByMonth(String accountId, String month) {
        return financeStatRepository.findByAccountIdAndStatMonth(accountId, month);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getFinancialOverview(String accountId) {
        Map<String, Object> overview = new HashMap<>();

        YearMonth currentMonth = YearMonth.now();
        LocalDateTime startTime = currentMonth.atDay(1).atStartOfDay();
        LocalDateTime endTime = currentMonth.atEndOfMonth().atTime(23, 59, 59);

        BigDecimal monthlyIncome = recordRepository.sumIncomeByAccountIdAndTimeRange(accountId, startTime, endTime);
        BigDecimal monthlyExpense = recordRepository.sumExpenseByAccountIdAndTimeRange(accountId, startTime, endTime);
        Long recordCount = recordRepository.countByAccountId(accountId);

        List<Object[]> categoryStats = recordRepository.sumByCategoryAndTimeRange(accountId, startTime, endTime);
        Map<String, BigDecimal> categoryMap = new HashMap<>();
        for (Object[] stat : categoryStats) {
            categoryMap.put((String) stat[0], (BigDecimal) stat[1]);
        }

        overview.put("account_id", accountId);
        overview.put("month", currentMonth.toString());
        overview.put("monthly_income", monthlyIncome);
        overview.put("monthly_expense", monthlyExpense);
        overview.put("monthly_balance", monthlyIncome.subtract(monthlyExpense));
        overview.put("total_records", recordCount);
        overview.put("category_stats", categoryMap);

        log.info("获取财务概览: accountId={}", accountId);
        return overview;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTrendAnalysis(String accountId, int months) {
        Map<String, Object> trend = new HashMap<>();
        List<Map<String, Object>> monthlyData = new java.util.ArrayList<>();

        YearMonth currentMonth = YearMonth.now();

        for (int i = months - 1; i >= 0; i--) {
            YearMonth month = currentMonth.minusMonths(i);
            LocalDateTime startTime = month.atDay(1).atStartOfDay();
            LocalDateTime endTime = month.atEndOfMonth().atTime(23, 59, 59);

            BigDecimal income = recordRepository.sumIncomeByAccountIdAndTimeRange(accountId, startTime, endTime);
            BigDecimal expense = recordRepository.sumExpenseByAccountIdAndTimeRange(accountId, startTime, endTime);

            Map<String, Object> monthData = new HashMap<>();
            monthData.put("month", month.toString());
            monthData.put("income", income);
            monthData.put("expense", expense);
            monthData.put("balance", income.subtract(expense));
            monthlyData.add(monthData);
        }

        trend.put("account_id", accountId);
        trend.put("months", months);
        trend.put("data", monthlyData);

        log.info("获取趋势分析: accountId={}, months={}", accountId, months);
        return trend;
    }

    private Map<String, BigDecimal> parseCategoryStat(String json) {
        if (json == null || json.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("解析分类统计失败", e);
            return new HashMap<>();
        }
    }

    private String serializeCategoryStat(Map<String, BigDecimal> categoryStat) {
        try {
            return objectMapper.writeValueAsString(categoryStat);
        } catch (JsonProcessingException e) {
            log.warn("序列化分类统计失败", e);
            return "{}";
        }
    }
}
