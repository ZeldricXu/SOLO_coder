package com.finance.config;

import com.finance.entity.*;
import com.finance.repository.*;
import com.finance.util.IdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AccountTypeRepository accountTypeRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final ValidationRuleRepository validationRuleRepository;
    private final BudgetTypeRepository budgetTypeRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        initializeAccountTypes();
        initializeCategories();
        initializeTransactionTypes();
        initializeValidationRules();
        initializeBudgetTypes();
        log.info("数据初始化完成");
    }

    private void initializeAccountTypes() {
        if (accountTypeRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        AccountType bankType = AccountType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode("bank")
                .typeName("银行账户")
                .typeDescription("银行储蓄卡、信用卡等账户")
                .typeStatus("active")
                .createdAt(now)
                .build();
        accountTypeRepository.save(bankType);

        AccountType cashType = AccountType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode("cash")
                .typeName("现金账户")
                .typeDescription("现金、零钱等账户")
                .typeStatus("active")
                .createdAt(now)
                .build();
        accountTypeRepository.save(cashType);

        AccountType creditType = AccountType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode("credit")
                .typeName("信用账户")
                .typeDescription("信用卡、花呗等信用账户")
                .typeStatus("active")
                .createdAt(now)
                .build();
        accountTypeRepository.save(creditType);

        AccountType investType = AccountType.builder()
                .typeId(IdGenerator.generateTypeId())
                .typeCode("invest")
                .typeName("投资账户")
                .typeDescription("股票、基金、理财等投资账户")
                .typeStatus("active")
                .createdAt(now)
                .build();
        accountTypeRepository.save(investType);

        log.info("账户类型初始化完成");
    }

    private void initializeCategories() {
        if (categoryRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        Category foodCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("餐饮")
                .categoryType("expense")
                .categoryParent("living")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(foodCategory);

        Category transportCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("交通")
                .categoryType("expense")
                .categoryParent("living")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(transportCategory);

        Category shoppingCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("购物")
                .categoryType("expense")
                .categoryParent("living")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(shoppingCategory);

        Category entertainmentCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("娱乐")
                .categoryType("expense")
                .categoryParent("living")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(entertainmentCategory);

        Category housingCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("住房")
                .categoryType("expense")
                .categoryParent("fixed")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(housingCategory);

        Category utilitiesCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("水电")
                .categoryType("expense")
                .categoryParent("fixed")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(utilitiesCategory);

        Category salaryCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("工资")
                .categoryType("income")
                .categoryParent("regular")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(salaryCategory);

        Category bonusCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("奖金")
                .categoryType("income")
                .categoryParent("irregular")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(bonusCategory);

        Category investmentCategory = Category.builder()
                .categoryId(IdGenerator.generateCategoryId())
                .categoryName("投资收益")
                .categoryType("income")
                .categoryParent("irregular")
                .categoryStatus("active")
                .createdAt(now)
                .build();
        categoryRepository.save(investmentCategory);

        log.info("分类初始化完成");
    }

    private void initializeTransactionTypes() {
        if (transactionTypeRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        TransactionType incomeType = TransactionType.builder()
                .typeId(IdGenerator.generateId("ttype"))
                .typeCode("income")
                .typeName("收入")
                .typeDirection("income")
                .affectsBalance(true)
                .requiresCategory(true)
                .typeDescription("收入记录，增加账户余额")
                .typeStatus("active")
                .createdAt(now)
                .build();
        transactionTypeRepository.save(incomeType);

        TransactionType expenseType = TransactionType.builder()
                .typeId(IdGenerator.generateId("ttype"))
                .typeCode("expense")
                .typeName("支出")
                .typeDirection("expense")
                .affectsBalance(true)
                .requiresCategory(true)
                .typeDescription("支出记录，减少账户余额")
                .typeStatus("active")
                .createdAt(now)
                .build();
        transactionTypeRepository.save(expenseType);

        TransactionType transferType = TransactionType.builder()
                .typeId(IdGenerator.generateId("ttype"))
                .typeCode("transfer")
                .typeName("转账")
                .typeDirection("neutral")
                .affectsBalance(false)
                .requiresCategory(false)
                .typeDescription("转账记录，不影响账户余额")
                .typeStatus("active")
                .createdAt(now)
                .build();
        transactionTypeRepository.save(transferType);

        TransactionType refundType = TransactionType.builder()
                .typeId(IdGenerator.generateId("ttype"))
                .typeCode("refund")
                .typeName("退款")
                .typeDirection("income")
                .affectsBalance(true)
                .requiresCategory(true)
                .typeDescription("退款记录，增加账户余额")
                .typeStatus("active")
                .createdAt(now)
                .build();
        transactionTypeRepository.save(refundType);

        log.info("收支类型初始化完成");
    }

    private void initializeValidationRules() {
        if (validationRuleRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        try {
            Map<String, Object> incomeSourceConfig = new HashMap<>();
            incomeSourceConfig.put("allowed_sources", Arrays.asList("工资", "奖金", "投资收益"));
            incomeSourceConfig.put("require_valid_source", false);
            incomeSourceConfig.put("require_non_empty_source", true);

            ValidationRule incomeSourceRule = ValidationRule.builder()
                    .ruleId(IdGenerator.generateId("vrule"))
                    .ruleName("收入来源校验")
                    .transactionTypeCode("income")
                    .ruleType("SOURCE_VALIDATION")
                    .ruleConfig(objectMapper.writeValueAsString(incomeSourceConfig))
                    .rulePriority(10)
                    .ruleStatus("active")
                    .createdAt(now)
                    .build();
            validationRuleRepository.save(incomeSourceRule);

            Map<String, Object> expenseCategoryConfig = new HashMap<>();
            expenseCategoryConfig.put("require_valid_category", true);
            expenseCategoryConfig.put("strict_mode", false);
            expenseCategoryConfig.put("required_categories", Arrays.asList("餐饮", "交通", "购物", "娱乐", "住房", "水电"));

            ValidationRule expenseCategoryRule = ValidationRule.builder()
                    .ruleId(IdGenerator.generateId("vrule"))
                    .ruleName("支出分类校验")
                    .transactionTypeCode("expense")
                    .ruleType("CATEGORY_VALIDATION")
                    .ruleConfig(objectMapper.writeValueAsString(expenseCategoryConfig))
                    .rulePriority(10)
                    .ruleStatus("active")
                    .createdAt(now)
                    .build();
            validationRuleRepository.save(expenseCategoryRule);

            Map<String, Object> amountConfig = new HashMap<>();
            amountConfig.put("min_amount", 0.01);
            amountConfig.put("max_amount", 999999999.99);

            ValidationRule amountRule = ValidationRule.builder()
                    .ruleId(IdGenerator.generateId("vrule"))
                    .ruleName("金额范围校验")
                    .transactionTypeCode("income")
                    .ruleType("AMOUNT_VALIDATION")
                    .ruleConfig(objectMapper.writeValueAsString(amountConfig))
                    .rulePriority(20)
                    .ruleStatus("active")
                    .createdAt(now)
                    .build();
            validationRuleRepository.save(amountRule);

            ValidationRule expenseAmountRule = ValidationRule.builder()
                    .ruleId(IdGenerator.generateId("vrule"))
                    .ruleName("支出金额范围校验")
                    .transactionTypeCode("expense")
                    .ruleType("AMOUNT_VALIDATION")
                    .ruleConfig(objectMapper.writeValueAsString(amountConfig))
                    .rulePriority(20)
                    .ruleStatus("active")
                    .createdAt(now)
                    .build();
            validationRuleRepository.save(expenseAmountRule);

            log.info("校验规则初始化完成");
        } catch (JsonProcessingException e) {
            log.error("初始化校验规则失败", e);
        }
    }

    private void initializeBudgetTypes() {
        if (budgetTypeRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        BudgetType importantType = BudgetType.builder()
                .budgetTypeId(IdGenerator.generateId("btype"))
                .budgetTypeCode("important")
                .budgetTypeName("重要预算")
                .categoryPattern("餐饮|住房")
                .priorityLevel("important")
                .reminderFrequencyMinutes(5)
                .maxRemindersPerDay(10)
                .typeDescription("高频提醒的重要预算类别")
                .typeStatus("active")
                .createdAt(now)
                .build();
        budgetTypeRepository.save(importantType);

        BudgetType normalType = BudgetType.builder()
                .budgetTypeId(IdGenerator.generateId("btype"))
                .budgetTypeCode("normal")
                .budgetTypeName("普通预算")
                .categoryPattern("交通|购物|娱乐|水电")
                .priorityLevel("normal")
                .reminderFrequencyMinutes(30)
                .maxRemindersPerDay(3)
                .typeDescription("低频提醒的普通预算类别")
                .typeStatus("active")
                .createdAt(now)
                .build();
        budgetTypeRepository.save(normalType);

        BudgetType lowType = BudgetType.builder()
                .budgetTypeId(IdGenerator.generateId("btype"))
                .budgetTypeCode("low_priority")
                .budgetTypeName("低优先级预算")
                .categoryPattern("*")
                .priorityLevel("low")
                .reminderFrequencyMinutes(60)
                .maxRemindersPerDay(1)
                .typeDescription("最低频率提醒的预算类别")
                .typeStatus("active")
                .createdAt(now)
                .build();
        budgetTypeRepository.save(lowType);

        log.info("预算类型初始化完成");
    }
}
