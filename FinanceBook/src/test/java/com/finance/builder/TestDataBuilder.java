package com.finance.builder;

import com.finance.dto.BudgetSetRequest;
import com.finance.dto.RecordCreateRequest;
import com.finance.entity.*;
import com.finance.util.IdGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

public class TestDataBuilder {

    public static class AccountTestData {
        public static final String DEFAULT_ACCOUNT_ID = "account_test_001";
        public static final String DEFAULT_ACCOUNT_NAME = "测试主账户";
        public static final String BANK_ACCOUNT_TYPE = "bank";
        public static final String CASH_ACCOUNT_TYPE = "cash";
        public static final String DEFAULT_CURRENCY = "CNY";
        public static final BigDecimal DEFAULT_BALANCE = new BigDecimal("10000.00");
        public static final String ACTIVE_STATUS = "active";
        public static final String FROZEN_STATUS = "frozen";
    }

    public static class RecordTestData {
        public static final String INCOME_TYPE = "income";
        public static final String EXPENSE_TYPE = "expense";
        public static final String SALARY_CATEGORY = "工资";
        public static final String BONUS_CATEGORY = "奖金";
        public static final String FOOD_CATEGORY = "餐饮";
        public static final String TRANSPORT_CATEGORY = "交通";
        public static final String SHOPPING_CATEGORY = "购物";
        public static final BigDecimal INCOME_AMOUNT_LARGE = new BigDecimal("15000.00");
        public static final BigDecimal INCOME_AMOUNT_SMALL = new BigDecimal("5000.00");
        public static final BigDecimal EXPENSE_AMOUNT_LARGE = new BigDecimal("3000.00");
        public static final BigDecimal EXPENSE_AMOUNT_MEDIUM = new BigDecimal("1000.00");
        public static final BigDecimal EXPENSE_AMOUNT_SMALL = new BigDecimal("100.00");
    }

    public static class BudgetTestData {
        public static final String MONTHLY_PERIOD = "monthly";
        public static final String IMPORTANT_CATEGORY = "餐饮";
        public static final String NORMAL_CATEGORY = "交通";
        public static final BigDecimal IMPORTANT_BUDGET = new BigDecimal("5000.00");
        public static final BigDecimal NORMAL_BUDGET = new BigDecimal("1000.00");
        public static final BigDecimal BUDGET_USED_NORMAL = new BigDecimal("3000.00");
        public static final BigDecimal BUDGET_USED_EXCEED = new BigDecimal("6000.00");
    }

    public static class CategoryTestData {
        public static final String INCOME_CATEGORY_PARENT = "regular";
        public static final String EXPENSE_CATEGORY_PARENT = "living";
        public static final String CATEGORY_ACTIVE = "active";
        public static final String CATEGORY_INACTIVE = "inactive";
    }

    public static class ReminderTestData {
        public static final String BUDGET_LIMIT_TYPE = "budget_limit";
        public static final String LOW_BALANCE_TYPE = "low_balance";
        public static final String SENT_STATUS = "sent";
        public static final String PENDING_STATUS = "pending";
    }

    public static Account buildDefaultAccount() {
        return Account.builder()
                .accountId(IdGenerator.generateAccountId())
                .accountName(AccountTestData.DEFAULT_ACCOUNT_NAME)
                .accountType(AccountTestData.BANK_ACCOUNT_TYPE)
                .accountBalance(AccountTestData.DEFAULT_BALANCE)
                .accountStatus(AccountTestData.ACTIVE_STATUS)
                .accountCurrency(AccountTestData.DEFAULT_CURRENCY)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Account buildAccount(String accountId, String accountName, String accountType,
                                        BigDecimal balance, String status) {
        return Account.builder()
                .accountId(accountId)
                .accountName(accountName)
                .accountType(accountType)
                .accountBalance(balance)
                .accountStatus(status)
                .accountCurrency(AccountTestData.DEFAULT_CURRENCY)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Account buildActiveBankAccount(String accountId) {
        return buildAccount(accountId, "银行账户-" + accountId, AccountTestData.BANK_ACCOUNT_TYPE,
                AccountTestData.DEFAULT_BALANCE, AccountTestData.ACTIVE_STATUS);
    }

    public static Account buildFrozenAccount(String accountId) {
        return buildAccount(accountId, "已冻结账户-" + accountId, AccountTestData.BANK_ACCOUNT_TYPE,
                AccountTestData.DEFAULT_BALANCE, AccountTestData.FROZEN_STATUS);
    }

    public static Account buildCashAccount(String accountId) {
        return buildAccount(accountId, "现金账户-" + accountId, AccountTestData.CASH_ACCOUNT_TYPE,
                new BigDecimal("5000.00"), AccountTestData.ACTIVE_STATUS);
    }

    public static Account buildZeroBalanceAccount(String accountId) {
        return buildAccount(accountId, "零余额账户-" + accountId, AccountTestData.BANK_ACCOUNT_TYPE,
                BigDecimal.ZERO, AccountTestData.ACTIVE_STATUS);
    }

    public static RecordCreateRequest buildIncomeRecordRequest(String accountId, BigDecimal amount) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.INCOME_TYPE)
                .record_amount(amount)
                .record_category(RecordTestData.SALARY_CATEGORY)
                .record_desc("测试收入记录")
                .build();
    }

    public static RecordCreateRequest buildIncomeRecordRequest(String accountId, BigDecimal amount, String category) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.INCOME_TYPE)
                .record_amount(amount)
                .record_category(category)
                .record_desc("测试收入记录")
                .build();
    }

    public static RecordCreateRequest buildExpenseRecordRequest(String accountId, BigDecimal amount) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.EXPENSE_TYPE)
                .record_amount(amount)
                .record_category(RecordTestData.FOOD_CATEGORY)
                .record_desc("测试支出记录")
                .build();
    }

    public static RecordCreateRequest buildExpenseRecordRequest(String accountId, BigDecimal amount, String category) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.EXPENSE_TYPE)
                .record_amount(amount)
                .record_category(category)
                .record_desc("测试支出记录")
                .build();
    }

    public static RecordCreateRequest buildInvalidTypeRecordRequest(String accountId) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type("invalid_type")
                .record_amount(new BigDecimal("1000.00"))
                .record_category(RecordTestData.FOOD_CATEGORY)
                .record_desc("无效类型记录")
                .build();
    }

    public static RecordCreateRequest buildZeroAmountRecordRequest(String accountId) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.EXPENSE_TYPE)
                .record_amount(BigDecimal.ZERO)
                .record_category(RecordTestData.FOOD_CATEGORY)
                .record_desc("零金额记录")
                .build();
    }

    public static RecordCreateRequest buildNegativeAmountRecordRequest(String accountId) {
        return RecordCreateRequest.builder()
                .account_id(accountId)
                .record_type(RecordTestData.EXPENSE_TYPE)
                .record_amount(new BigDecimal("-100.00"))
                .record_category(RecordTestData.FOOD_CATEGORY)
                .record_desc("负金额记录")
                .build();
    }

    public static Record buildRecord(String recordId, String accountId, String recordType,
                                      BigDecimal amount, String category) {
        return Record.builder()
                .recordId(recordId)
                .accountId(accountId)
                .recordType(recordType)
                .recordAmount(amount)
                .recordCategory(category)
                .recordTime(LocalDateTime.now())
                .recordDesc("测试记录")
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Record buildIncomeRecord(String recordId, String accountId, BigDecimal amount) {
        return buildRecord(recordId, accountId, RecordTestData.INCOME_TYPE, amount, RecordTestData.SALARY_CATEGORY);
    }

    public static Record buildExpenseRecord(String recordId, String accountId, BigDecimal amount) {
        return buildRecord(recordId, accountId, RecordTestData.EXPENSE_TYPE, amount, RecordTestData.FOOD_CATEGORY);
    }

    public static BudgetSetRequest buildBudgetRequest(String accountId, String category, BigDecimal amount) {
        return BudgetSetRequest.builder()
                .account_id(accountId)
                .budget_category(category)
                .budget_amount(amount)
                .budget_period(BudgetTestData.MONTHLY_PERIOD)
                .build();
    }

    public static BudgetSetRequest buildImportantBudgetRequest(String accountId) {
        return buildBudgetRequest(accountId, BudgetTestData.IMPORTANT_CATEGORY, BudgetTestData.IMPORTANT_BUDGET);
    }

    public static BudgetSetRequest buildNormalBudgetRequest(String accountId) {
        return buildBudgetRequest(accountId, BudgetTestData.NORMAL_CATEGORY, BudgetTestData.NORMAL_BUDGET);
    }

    public static Budget buildBudget(String budgetId, String accountId, String category,
                                      BigDecimal budgetAmount, BigDecimal usedAmount) {
        return Budget.builder()
                .budgetId(budgetId)
                .accountId(accountId)
                .budgetCategory(category)
                .budgetAmount(budgetAmount)
                .budgetPeriod(YearMonth.now().toString())
                .budgetUsed(usedAmount)
                .budgetRemaining(budgetAmount.subtract(usedAmount))
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Budget buildNormalBudget(String budgetId, String accountId) {
        return buildBudget(budgetId, accountId, BudgetTestData.IMPORTANT_CATEGORY,
                BudgetTestData.IMPORTANT_BUDGET, BudgetTestData.BUDGET_USED_NORMAL);
    }

    public static Budget buildExceededBudget(String budgetId, String accountId) {
        return buildBudget(budgetId, accountId, BudgetTestData.IMPORTANT_CATEGORY,
                BudgetTestData.IMPORTANT_BUDGET, BudgetTestData.BUDGET_USED_EXCEED);
    }

    public static Budget buildZeroUsedBudget(String budgetId, String accountId) {
        return buildBudget(budgetId, accountId, BudgetTestData.IMPORTANT_CATEGORY,
                BudgetTestData.IMPORTANT_BUDGET, BigDecimal.ZERO);
    }

    public static Category buildCategory(String categoryId, String categoryName, String categoryType) {
        return Category.builder()
                .categoryId(categoryId)
                .categoryName(categoryName)
                .categoryType(categoryType)
                .categoryParent(categoryType.equals(RecordTestData.INCOME_TYPE)
                        ? CategoryTestData.INCOME_CATEGORY_PARENT
                        : CategoryTestData.EXPENSE_CATEGORY_PARENT)
                .categoryStatus(CategoryTestData.CATEGORY_ACTIVE)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Category buildIncomeCategory(String categoryId, String categoryName) {
        return buildCategory(categoryId, categoryName, RecordTestData.INCOME_TYPE);
    }

    public static Category buildExpenseCategory(String categoryId, String categoryName) {
        return buildCategory(categoryId, categoryName, RecordTestData.EXPENSE_TYPE);
    }

    public static Category buildInactiveCategory(String categoryId, String categoryName) {
        Category category = buildCategory(categoryId, categoryName, RecordTestData.EXPENSE_TYPE);
        category.setCategoryStatus(CategoryTestData.CATEGORY_INACTIVE);
        return category;
    }

    public static Reminder buildReminder(String reminderId, String accountId, String reminderType) {
        return Reminder.builder()
                .reminderId(reminderId)
                .accountId(accountId)
                .reminderType(reminderType)
                .reminderContent("测试提醒内容")
                .reminderTime(LocalDateTime.now())
                .reminderStatus(ReminderTestData.SENT_STATUS)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Reminder buildBudgetReminder(String reminderId, String accountId) {
        return buildReminder(reminderId, accountId, ReminderTestData.BUDGET_LIMIT_TYPE);
    }

    public static Reminder buildPendingReminder(String reminderId, String accountId) {
        Reminder reminder = buildReminder(reminderId, accountId, ReminderTestData.BUDGET_LIMIT_TYPE);
        reminder.setReminderStatus(ReminderTestData.PENDING_STATUS);
        return reminder;
    }

    public static Report buildReport(String reportId, String accountId, BigDecimal income, BigDecimal expense) {
        return Report.builder()
                .reportId(reportId)
                .accountId(accountId)
                .reportPeriod(YearMonth.now().toString())
                .reportIncome(income)
                .reportExpense(expense)
                .reportBalance(income.subtract(expense))
                .generatedAt(LocalDateTime.now())
                .build();
    }

    public static FinanceStat buildFinanceStat(String statId, String accountId, Long recordCount,
                                                BigDecimal incomeTotal, BigDecimal expenseTotal) {
        return FinanceStat.builder()
                .statId(statId)
                .accountId(accountId)
                .statMonth(YearMonth.now().toString())
                .recordCount(recordCount)
                .incomeTotal(incomeTotal)
                .expenseTotal(expenseTotal)
                .categoryStat("{}")
                .build();
    }

    public static AccountType buildAccountType(String typeId, String typeCode, String typeName) {
        return AccountType.builder()
                .typeId(typeId)
                .typeCode(typeCode)
                .typeName(typeName)
                .typeDescription("测试账户类型")
                .typeStatus("active")
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static String generateUniqueId(String prefix) {
        return IdGenerator.generateId(prefix);
    }
}
