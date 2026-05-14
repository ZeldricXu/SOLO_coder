package com.finance.service;

import com.finance.dto.BudgetSetRequest;
import com.finance.dto.BudgetSetResponse;
import com.finance.entity.Budget;
import com.finance.exception.FinanceException;
import com.finance.repository.BudgetRepository;
import com.finance.repository.RecordRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final RecordRepository recordRepository;
    private final AccountService accountService;
    private final ReminderService reminderService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final BudgetTypeService budgetTypeService;

    @Transactional
    public BudgetSetResponse setBudget(BudgetSetRequest request) {
        String accountId = request.getAccount_id();
        String category = request.getBudget_category();
        BigDecimal amount = request.getBudget_amount();
        String period = request.getBudget_period() != null ? request.getBudget_period() : "monthly";

        accountService.getAccountById(accountId);

        String currentPeriod = getCurrentPeriod();

        Optional<Budget> existingBudget = budgetRepository
                .findByAccountIdAndBudgetCategoryAndBudgetPeriod(accountId, category, currentPeriod);

        Budget budget;
        LocalDateTime now = LocalDateTime.now();

        if (existingBudget.isPresent()) {
            budget = existingBudget.get();
            budget.setBudgetAmount(amount);
            budget.setBudgetRemaining(amount.subtract(budget.getBudgetUsed()));
            budget.setUpdatedAt(now);
        } else {
            BigDecimal usedAmount = calculateUsedAmount(accountId, category, currentPeriod);
            budget = Budget.builder()
                    .budgetId(IdGenerator.generateBudgetId())
                    .accountId(accountId)
                    .budgetCategory(category)
                    .budgetAmount(amount)
                    .budgetPeriod(currentPeriod)
                    .budgetUsed(usedAmount)
                    .budgetRemaining(amount.subtract(usedAmount))
                    .createdAt(now)
                    .build();
        }

        Budget saved = budgetRepository.save(budget);
        log.info("设置预算成功: budgetId={}, category={}, amount={}", saved.getBudgetId(), category, amount);

        analysisService.updateBudgetAnalysis(accountId, category, amount);
        historyService.recordHistory(accountId, "budget_set", "设置预算: " + category + " = " + amount);

        return BudgetSetResponse.builder()
                .budget_id(saved.getBudgetId())
                .remaining(saved.getBudgetRemaining())
                .build();
    }

    @Transactional(readOnly = true)
    public Budget getBudgetById(String budgetId) {
        return budgetRepository.findById(budgetId)
                .orElseThrow(() -> new FinanceException(404, "预算不存在: " + budgetId));
    }

    @Transactional(readOnly = true)
    public List<Budget> getBudgetsByAccount(String accountId) {
        return budgetRepository.findByAccountId(accountId);
    }

    @Transactional(readOnly = true)
    public Optional<Budget> getBudgetByCategory(String accountId, String category) {
        return budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                accountId, category, getCurrentPeriod());
    }

    @Transactional
    public void checkAndUpdateBudget(String accountId, String category, BigDecimal expenseAmount) {
        Optional<Budget> budgetOpt = getBudgetByCategory(accountId, category);

        if (budgetOpt.isPresent()) {
            Budget budget = budgetOpt.get();
            BigDecimal newUsed = budget.getBudgetUsed().add(expenseAmount);
            BigDecimal newRemaining = budget.getBudgetAmount().subtract(newUsed);

            budget.setBudgetUsed(newUsed);
            budget.setBudgetRemaining(newRemaining);
            budget.setUpdatedAt(LocalDateTime.now());

            budgetRepository.save(budget);

            if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
                log.warn("预算超限: accountId={}, category={}, used={}, budget={}",
                        accountId, category, newUsed, budget.getBudgetAmount());

                if (budgetTypeService.shouldSendReminder(accountId, category)) {
                    reminderService.sendBudgetReminder(accountId, category, budget.getBudgetAmount(), newUsed);
                    log.info("发送预算提醒: category={}, priority={}", category, budgetTypeService.getPriorityLevel(category));
                } else {
                    log.info("达到每日提醒上限，跳过发送提醒: category={}", category);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    private BigDecimal calculateUsedAmount(String accountId, String category, String period) {
        YearMonth yearMonth = YearMonth.parse(period);
        LocalDateTime startTime = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime endTime = yearMonth.atEndOfMonth().atTime(23, 59, 59);

        List<Object[]> categoryStats = recordRepository.sumByCategoryAndTimeRange(accountId, startTime, endTime);

        for (Object[] stat : categoryStats) {
            if (category.equals(stat[0])) {
                return (BigDecimal) stat[1];
            }
        }
        return BigDecimal.ZERO;
    }

    private String getCurrentPeriod() {
        return YearMonth.now().toString();
    }
}
