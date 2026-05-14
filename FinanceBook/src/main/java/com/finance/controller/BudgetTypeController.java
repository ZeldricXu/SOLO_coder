package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.BudgetType;
import com.finance.service.BudgetTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/budget-types")
@RequiredArgsConstructor
public class BudgetTypeController {

    private final BudgetTypeService budgetTypeService;

    @GetMapping
    public ApiResponse<List<BudgetType>> getAllBudgetTypes() {
        List<BudgetType> types = budgetTypeService.getAllBudgetTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/active")
    public ApiResponse<List<BudgetType>> getActiveBudgetTypes() {
        List<BudgetType> types = budgetTypeService.getActiveBudgetTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/priority/{priorityLevel}")
    public ApiResponse<List<BudgetType>> getBudgetTypesByPriority(@PathVariable String priorityLevel) {
        List<BudgetType> types = budgetTypeService.getBudgetTypesByPriority(priorityLevel);
        return ApiResponse.success(types);
    }

    @GetMapping("/{typeCode}")
    public ApiResponse<BudgetType> getBudgetType(@PathVariable String typeCode) {
        BudgetType type = budgetTypeService.getBudgetTypeByCode(typeCode);
        return ApiResponse.success(type);
    }

    @GetMapping("/for-category/{category}")
    public ApiResponse<Map<String, Object>> getBudgetTypeForCategory(@PathVariable String category) {
        BudgetType type = budgetTypeService.getBudgetTypeForCategory(category);
        int frequency = budgetTypeService.calculateReminderFrequency(category);
        int maxReminders = budgetTypeService.getMaxRemindersPerDay(category);
        String priority = budgetTypeService.getPriorityLevel(category);
        boolean shouldSend = budgetTypeService.shouldSendReminder("test_account", category);

        Map<String, Object> result = Map.of(
                "budget_type", type != null ? type.getBudgetTypeName() : "default",
                "priority_level", priority,
                "reminder_frequency_minutes", frequency,
                "max_reminders_per_day", maxReminders,
                "should_send_reminder", shouldSend,
                "is_important", budgetTypeService.isImportantBudget(category),
                "is_normal", budgetTypeService.isNormalBudget(category),
                "is_low_priority", budgetTypeService.isLowPriorityBudget(category)
        );

        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<BudgetType> createBudgetType(@RequestBody Map<String, Object> request) {
        String typeCode = (String) request.get("budget_type_code");
        String typeName = (String) request.get("budget_type_name");
        String categoryPattern = (String) request.get("category_pattern");
        String priorityLevel = (String) request.getOrDefault("priority_level", "normal");
        Integer frequency = (Integer) request.getOrDefault("reminder_frequency_minutes", 30);
        Integer maxReminders = (Integer) request.getOrDefault("max_reminders_per_day", 3);
        String description = (String) request.get("type_description");

        BudgetType type = budgetTypeService.createBudgetType(
                typeCode, typeName, categoryPattern, priorityLevel, frequency, maxReminders, description);
        return ApiResponse.success(type);
    }

    @PutMapping("/{typeCode}")
    public ApiResponse<BudgetType> updateBudgetType(@PathVariable String typeCode, @RequestBody Map<String, Object> request) {
        String typeName = (String) request.get("budget_type_name");
        String categoryPattern = (String) request.get("category_pattern");
        String priorityLevel = (String) request.get("priority_level");
        Integer frequency = (Integer) request.get("reminder_frequency_minutes");
        Integer maxReminders = (Integer) request.get("max_reminders_per_day");
        String status = (String) request.get("type_status");

        BudgetType type = budgetTypeService.updateBudgetType(
                typeCode, typeName, categoryPattern, priorityLevel, frequency, maxReminders, status);
        return ApiResponse.success(type);
    }
}
