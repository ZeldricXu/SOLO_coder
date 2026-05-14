package com.finance.service;

import com.finance.entity.BudgetType;
import com.finance.exception.FinanceException;
import com.finance.repository.BudgetTypeRepository;
import com.finance.repository.ReminderRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetTypeService {

    private final BudgetTypeRepository budgetTypeRepository;
    private final ReminderRepository reminderRepository;

    public static final String PRIORITY_IMPORTANT = "important";
    public static final String PRIORITY_NORMAL = "normal";
    public static final String PRIORITY_LOW = "low";

    @Transactional
    public BudgetType createBudgetType(String budgetTypeCode, String budgetTypeName,
                                        String categoryPattern, String priorityLevel,
                                        Integer reminderFrequencyMinutes, Integer maxRemindersPerDay,
                                        String description) {
        if (budgetTypeRepository.existsByBudgetTypeCode(budgetTypeCode)) {
            throw new FinanceException(400, "预算类型已存在: " + budgetTypeCode);
        }

        BudgetType type = BudgetType.builder()
                .budgetTypeId(IdGenerator.generateId("btype"))
                .budgetTypeCode(budgetTypeCode)
                .budgetTypeName(budgetTypeName)
                .categoryPattern(categoryPattern)
                .priorityLevel(priorityLevel)
                .reminderFrequencyMinutes(reminderFrequencyMinutes)
                .maxRemindersPerDay(maxRemindersPerDay)
                .typeDescription(description)
                .typeStatus("active")
                .createdAt(LocalDateTime.now())
                .build();

        BudgetType saved = budgetTypeRepository.save(type);
        log.info("创建预算类型成功: code={}, priority={}, frequency={}min",
                budgetTypeCode, priorityLevel, reminderFrequencyMinutes);
        return saved;
    }

    @Transactional(readOnly = true)
    public BudgetType getBudgetTypeByCode(String budgetTypeCode) {
        return budgetTypeRepository.findByBudgetTypeCode(budgetTypeCode)
                .orElseThrow(() -> new FinanceException(404, "预算类型不存在: " + budgetTypeCode));
    }

    @Transactional(readOnly = true)
    public List<BudgetType> getAllBudgetTypes() {
        return budgetTypeRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<BudgetType> getActiveBudgetTypes() {
        return budgetTypeRepository.findByTypeStatus("active");
    }

    @Transactional(readOnly = true)
    public List<BudgetType> getBudgetTypesByPriority(String priorityLevel) {
        return budgetTypeRepository.findByPriorityLevel(priorityLevel);
    }

    @Transactional(readOnly = true)
    public BudgetType getBudgetTypeForCategory(String category) {
        List<BudgetType> types = getActiveBudgetTypes();

        for (BudgetType type : types) {
            if (matchesPattern(category, type.getCategoryPattern())) {
                return type;
            }
        }

        return getDefaultBudgetType();
    }

    private boolean matchesPattern(String category, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }

        if ("*".equals(pattern)) {
            return true;
        }

        if (pattern.contains("|")) {
            String[] patterns = pattern.split("\\|");
            for (String p : patterns) {
                if (category.equals(p.trim())) {
                    return true;
                }
            }
            return false;
        }

        return category.equals(pattern);
    }

    private BudgetType getDefaultBudgetType() {
        return budgetTypeRepository.findByBudgetTypeCode("default_budget")
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public int calculateReminderFrequency(String category) {
        BudgetType type = getBudgetTypeForCategory(category);
        if (type != null) {
            return type.getReminderFrequencyMinutes();
        }
        return 60;
    }

    @Transactional(readOnly = true)
    public int getMaxRemindersPerDay(String category) {
        BudgetType type = getBudgetTypeForCategory(category);
        if (type != null) {
            return type.getMaxRemindersPerDay();
        }
        return 3;
    }

    @Transactional(readOnly = true)
    public String getPriorityLevel(String category) {
        BudgetType type = getBudgetTypeForCategory(category);
        if (type != null) {
            return type.getPriorityLevel();
        }
        return PRIORITY_NORMAL;
    }

    @Transactional(readOnly = true)
    public boolean shouldSendReminder(String accountId, String category) {
        BudgetType type = getBudgetTypeForCategory(category);
        if (type == null) {
            return true;
        }

        int todayReminderCount = countTodayReminders(accountId, category);
        return todayReminderCount < type.getMaxRemindersPerDay();
    }

    private int countTodayReminders(String accountId, String category) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);

        List<com.finance.entity.Reminder> reminders = reminderRepository.findByAccountIdOrderByReminderTimeDesc(accountId);
        return (int) reminders.stream()
                .filter(r -> "budget_limit".equals(r.getReminderType()))
                .filter(r -> !r.getReminderTime().isBefore(startOfDay) && !r.getReminderTime().isAfter(endOfDay))
                .count();
    }

    @Transactional(readOnly = true)
    public boolean isImportantBudget(String category) {
        return PRIORITY_IMPORTANT.equals(getPriorityLevel(category));
    }

    @Transactional(readOnly = true)
    public boolean isNormalBudget(String category) {
        return PRIORITY_NORMAL.equals(getPriorityLevel(category));
    }

    @Transactional(readOnly = true)
    public boolean isLowPriorityBudget(String category) {
        return PRIORITY_LOW.equals(getPriorityLevel(category));
    }

    @Transactional
    public BudgetType updateBudgetType(String budgetTypeCode, String budgetTypeName,
                                        String categoryPattern, String priorityLevel,
                                        Integer reminderFrequencyMinutes, Integer maxRemindersPerDay,
                                        String status) {
        BudgetType type = getBudgetTypeByCode(budgetTypeCode);

        if (budgetTypeName != null) type.setBudgetTypeName(budgetTypeName);
        if (categoryPattern != null) type.setCategoryPattern(categoryPattern);
        if (priorityLevel != null) type.setPriorityLevel(priorityLevel);
        if (reminderFrequencyMinutes != null) type.setReminderFrequencyMinutes(reminderFrequencyMinutes);
        if (maxRemindersPerDay != null) type.setMaxRemindersPerDay(maxRemindersPerDay);
        if (status != null) type.setTypeStatus(status);
        type.setUpdatedAt(LocalDateTime.now());

        return budgetTypeRepository.save(type);
    }
}
