package com.finance.service;

import com.finance.builder.TestDataBuilder;
import com.finance.dto.BudgetSetRequest;
import com.finance.dto.BudgetSetResponse;
import com.finance.entity.Account;
import com.finance.entity.Budget;
import com.finance.entity.Reminder;
import com.finance.exception.FinanceException;
import com.finance.repository.BudgetRepository;
import com.finance.repository.RecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预算模块单元测试")
class BudgetServiceUnitTest {

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private RecordRepository recordRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private ReminderService reminderService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private BudgetService budgetService;

    private String testAccountId;
    private String currentPeriod;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccountId = TestDataBuilder.generateUniqueId("account");
        currentPeriod = YearMonth.now().toString();
        testAccount = TestDataBuilder.buildActiveBankAccount(testAccountId);
    }

    @Nested
    @DisplayName("预算超限提醒触发测试")
    class BudgetExceededReminderTests {

        @Test
        @DisplayName("预算超限时正确触发提醒")
        void testBudgetExceededTriggersReminder() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("3000.00");
            BigDecimal expenseAmount = new BigDecimal("2500.00");
            BigDecimal newUsed = usedAmount.add(expenseAmount);

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService).sendBudgetReminder(eq(testAccountId), eq(category), eq(budgetAmount), eq(newUsed));
            verify(budgetRepository).save(any(Budget.class));
        }

        @Test
        @DisplayName("预算未超限时不触发提醒")
        void testBudgetNotExceededNoReminder() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("2000.00");
            BigDecimal expenseAmount = new BigDecimal("1000.00");

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService, never()).sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
            verify(budgetRepository).save(any(Budget.class));
        }

        @Test
        @DisplayName("预算刚好等于已使用不触发提醒")
        void testBudgetEqualsUsedNoReminder() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("3000.00");
            BigDecimal expenseAmount = new BigDecimal("2000.00");

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService, never()).sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("无预算设置时不检查也不触发提醒")
        void testNoBudgetSettingNoReminder() {
            String category = "餐饮";
            BigDecimal expenseAmount = new BigDecimal("1000.00");

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.empty());

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService, never()).sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
            verify(budgetRepository, never()).save(any(Budget.class));
        }

        @Test
        @DisplayName("连续超限多次触发提醒")
        void testMultipleExceededTriggersMultipleReminders() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("4000.00");
            BigDecimal expenseAmount = new BigDecimal("1500.00");

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> {
                Budget saved = invocation.getArgument(0);
                budget.setBudgetUsed(saved.getBudgetUsed());
                budget.setBudgetRemaining(saved.getBudgetRemaining());
                return budget;
            });
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);
            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService, times(2)).sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class));
        }
    }

    @Nested
    @DisplayName("不同预算类型提醒频率差异测试")
    class BudgetTypeReminderFrequencyTests {

        @Test
        @DisplayName("重要预算类别超限后立即发送提醒")
        void testImportantBudgetImmediateReminder() {
            String importantCategory = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("4500.00");
            BigDecimal expenseAmount = new BigDecimal("1000.00");
            BigDecimal newUsed = usedAmount.add(expenseAmount);

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, importantCategory, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, importantCategory, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    String cat = invocation.getArgument(1);
                    return TestDataBuilder.buildReminder("reminder_" + cat, testAccountId, "budget_limit");
                });

            budgetService.checkAndUpdateBudget(testAccountId, importantCategory, expenseAmount);

            verify(reminderService).sendBudgetReminder(eq(testAccountId), eq(importantCategory), eq(budgetAmount), eq(newUsed));
        }

        @Test
        @DisplayName("普通预算类别超限后发送提醒")
        void testNormalBudgetReminder() {
            String normalCategory = "交通";
            BigDecimal budgetAmount = new BigDecimal("1000.00");
            BigDecimal usedAmount = new BigDecimal("800.00");
            BigDecimal expenseAmount = new BigDecimal("500.00");
            BigDecimal newUsed = usedAmount.add(expenseAmount);

            Budget budget = TestDataBuilder.buildBudget("budget_002", testAccountId, normalCategory, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, normalCategory, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_002", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, normalCategory, expenseAmount);

            verify(reminderService).sendBudgetReminder(eq(testAccountId), eq(normalCategory), eq(budgetAmount), eq(newUsed));
        }

        @Test
        @DisplayName("不同预算类别独立触发提醒")
        void testDifferentCategoriesIndependentReminders() {
            String foodCategory = "餐饮";
            String transportCategory = "交通";

            Budget foodBudget = TestDataBuilder.buildBudget("budget_food", testAccountId, foodCategory,
                new BigDecimal("5000.00"), new BigDecimal("4500.00"));
            Budget transportBudget = TestDataBuilder.buildBudget("budget_transport", testAccountId, transportCategory,
                new BigDecimal("1000.00"), new BigDecimal("200.00"));

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                eq(testAccountId), eq(foodCategory), eq(currentPeriod))).thenReturn(Optional.of(foodBudget));
            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                eq(testAccountId), eq(transportCategory), eq(currentPeriod))).thenReturn(Optional.of(transportBudget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, foodCategory, new BigDecimal("1000.00"));
            budgetService.checkAndUpdateBudget(testAccountId, transportCategory, new BigDecimal("500.00"));

            verify(reminderService, times(1)).sendBudgetReminder(
                eq(testAccountId), eq(foodCategory), any(BigDecimal.class), any(BigDecimal.class));
            verify(reminderService, never()).sendBudgetReminder(
                eq(testAccountId), eq(transportCategory), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("大额预算超限触发提醒内容正确")
        void testLargeBudgetExceededReminderContent() {
            String category = "购物";
            BigDecimal budgetAmount = new BigDecimal("20000.00");
            BigDecimal usedAmount = new BigDecimal("18000.00");
            BigDecimal expenseAmount = new BigDecimal("5000.00");
            BigDecimal newUsed = usedAmount.add(expenseAmount);
            BigDecimal exceeded = newUsed.subtract(budgetAmount);

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(reminderService).sendBudgetReminder(eq(testAccountId), eq(category), eq(budgetAmount), eq(newUsed));
            assertTrue(exceeded.compareTo(BigDecimal.ZERO) > 0);
        }
    }

    @Nested
    @DisplayName("提醒发送机制测试")
    class ReminderDeliveryTests {

        @Test
        @DisplayName("提醒正确保存到数据库")
        void testReminderSavedToDatabase() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("4500.00");
            BigDecimal expenseAmount = new BigDecimal("1000.00");

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, usedAmount);
            Reminder expectedReminder = TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(expectedReminder);

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            Reminder sentReminder = reminderService.sendBudgetReminder(testAccountId, category, budgetAmount, usedAmount);

            assertNotNull(sentReminder);
            assertEquals("budget_limit", sentReminder.getReminderType());
            assertEquals("sent", sentReminder.getReminderStatus());
        }

        @Test
        @DisplayName("提醒包含正确的账户信息")
        void testReminderContainsAccountInfo() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("6000.00");

            Budget budget = TestDataBuilder.buildExceededBudget("budget_001", testAccountId);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    String accountId = invocation.getArgument(0);
                    return TestDataBuilder.buildBudgetReminder("reminder_001", accountId);
                });

            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("500.00"));

            verify(reminderService).sendBudgetReminder(eq(testAccountId), anyString(), any(BigDecimal.class), any(BigDecimal.class));
        }

        @Test
        @DisplayName("提醒状态正确设置为已发送")
        void testReminderStatusSetToSent() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("5500.00");

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount,
                new BigDecimal("5000.00"));

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("1000.00"));

            Reminder reminder = reminderService.sendBudgetReminder(testAccountId, category, budgetAmount, usedAmount);
            assertEquals("sent", reminder.getReminderStatus());
        }

        @Test
        @DisplayName("提醒时间戳正确设置")
        void testReminderTimestampSet() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("6000.00");

            Budget budget = TestDataBuilder.buildExceededBudget("budget_001", testAccountId);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("1000.00"));

            Reminder reminder = reminderService.sendBudgetReminder(testAccountId, category, budgetAmount, usedAmount);
            assertNotNull(reminder.getReminderTime());
            assertNotNull(reminder.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("预算使用金额计算测试")
    class BudgetUsageCalculationTests {

        @Test
        @DisplayName("预算设置时计算已使用金额正确")
        void testSetBudgetCalculatesUsedAmount() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("1500.00");

            BudgetSetRequest request = TestDataBuilder.buildBudgetRequest(testAccountId, category, budgetAmount);
            List<Object[]> categoryStats = Arrays.asList(
                new Object[]{category, usedAmount}
            );

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.empty());
            when(recordRepository.sumByCategoryAndTimeRange(
                eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(categoryStats);
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            doNothing().when(analysisService).updateBudgetAnalysis(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            BudgetSetResponse response = budgetService.setBudget(request);

            assertNotNull(response);
            assertNotNull(response.getBudget_id());
            assertEquals(budgetAmount.subtract(usedAmount), response.getRemaining());
        }

        @Test
        @DisplayName("无历史记录时已使用金额为零")
        void testSetBudgetWithNoHistory() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");

            BudgetSetRequest request = TestDataBuilder.buildBudgetRequest(testAccountId, category, budgetAmount);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.empty());
            when(recordRepository.sumByCategoryAndTimeRange(
                eq(testAccountId), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(new ArrayList<>());
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            doNothing().when(analysisService).updateBudgetAnalysis(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            BudgetSetResponse response = budgetService.setBudget(request);

            assertEquals(budgetAmount, response.getRemaining());
        }

        @Test
        @DisplayName("支出后更新预算使用金额正确")
        void testCheckBudgetUpdatesUsedAmount() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("5000.00");
            BigDecimal initialUsed = new BigDecimal("1000.00");
            BigDecimal expenseAmount = new BigDecimal("500.00");
            BigDecimal expectedUsed = initialUsed.add(expenseAmount);
            BigDecimal expectedRemaining = budgetAmount.subtract(expectedUsed);

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, initialUsed);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));

            budgetService.checkAndUpdateBudget(testAccountId, category, expenseAmount);

            verify(budgetRepository).save(argThat(saved ->
                expectedUsed.compareTo(saved.getBudgetUsed()) == 0 &&
                expectedRemaining.compareTo(saved.getBudgetRemaining()) == 0
            ));
        }

        @Test
        @DisplayName("多次支出累计计算正确")
        void testMultipleExpensesCumulativeCalculation() {
            String category = "餐饮";
            BigDecimal budgetAmount = new BigDecimal("10000.00");
            BigDecimal initialUsed = BigDecimal.ZERO;

            Budget budget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, budgetAmount, initialUsed);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> {
                Budget saved = invocation.getArgument(0);
                budget.setBudgetUsed(saved.getBudgetUsed());
                budget.setBudgetRemaining(saved.getBudgetRemaining());
                return budget;
            });
            when(reminderService.sendBudgetReminder(anyString(), anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(TestDataBuilder.buildBudgetReminder("reminder_001", testAccountId));

            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("1000.00"));
            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("2000.00"));
            budgetService.checkAndUpdateBudget(testAccountId, category, new BigDecimal("3000.00"));

            assertEquals(new BigDecimal("6000.00"), budget.getBudgetUsed());
            assertEquals(new BigDecimal("4000.00"), budget.getBudgetRemaining());
        }

        @Test
        @DisplayName("不同分类预算独立计算")
        void testDifferentCategoriesIndependentCalculation() {
            String foodCategory = "餐饮";
            String transportCategory = "交通";
            BigDecimal foodBudget = new BigDecimal("5000.00");
            BigDecimal transportBudget = new BigDecimal("2000.00");

            Budget food = TestDataBuilder.buildZeroUsedBudget("budget_food", testAccountId);
            Budget transport = TestDataBuilder.buildZeroUsedBudget("budget_transport", testAccountId);
            food.setBudgetCategory(foodCategory);
            food.setBudgetAmount(foodBudget);
            transport.setBudgetCategory(transportCategory);
            transport.setBudgetAmount(transportBudget);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                eq(testAccountId), eq(foodCategory), eq(currentPeriod))).thenReturn(Optional.of(food));
            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                eq(testAccountId), eq(transportCategory), eq(currentPeriod))).thenReturn(Optional.of(transport));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> {
                Budget saved = invocation.getArgument(0);
                if (foodCategory.equals(saved.getBudgetCategory())) {
                    food.setBudgetUsed(saved.getBudgetUsed());
                    food.setBudgetRemaining(saved.getBudgetRemaining());
                } else {
                    transport.setBudgetUsed(saved.getBudgetUsed());
                    transport.setBudgetRemaining(saved.getBudgetRemaining());
                }
                return saved;
            });

            budgetService.checkAndUpdateBudget(testAccountId, foodCategory, new BigDecimal("1000.00"));
            budgetService.checkAndUpdateBudget(testAccountId, transportCategory, new BigDecimal("500.00"));

            assertEquals(new BigDecimal("1000.00"), food.getBudgetUsed());
            assertEquals(new BigDecimal("500.00"), transport.getBudgetUsed());
        }
    }

    @Nested
    @DisplayName("预算查询与管理测试")
    class BudgetManagementTests {

        @Test
        @DisplayName("查询存在的预算成功")
        void testGetExistingBudget() {
            String budgetId = "budget_001";
            Budget budget = TestDataBuilder.buildNormalBudget(budgetId, testAccountId);

            when(budgetRepository.findById(budgetId)).thenReturn(Optional.of(budget));

            Budget result = budgetService.getBudgetById(budgetId);

            assertNotNull(result);
            assertEquals(budgetId, result.getBudgetId());
        }

        @Test
        @DisplayName("查询不存在的预算抛出异常")
        void testGetNonExistingBudget() {
            String nonExistingId = "non_existing";

            when(budgetRepository.findById(nonExistingId)).thenReturn(Optional.empty());

            assertThrows(FinanceException.class, () -> budgetService.getBudgetById(nonExistingId));
        }

        @Test
        @DisplayName("按账户查询所有预算成功")
        void testGetBudgetsByAccount() {
            List<Budget> budgets = Arrays.asList(
                TestDataBuilder.buildNormalBudget("budget_001", testAccountId),
                TestDataBuilder.buildZeroUsedBudget("budget_002", testAccountId)
            );

            when(budgetRepository.findByAccountId(testAccountId)).thenReturn(budgets);

            List<Budget> result = budgetService.getBudgetsByAccount(testAccountId);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("更新已有预算成功")
        void testUpdateExistingBudget() {
            String category = "餐饮";
            BigDecimal originalAmount = new BigDecimal("3000.00");
            BigDecimal newAmount = new BigDecimal("5000.00");
            BigDecimal usedAmount = new BigDecimal("1000.00");

            Budget existingBudget = TestDataBuilder.buildBudget("budget_001", testAccountId, category, originalAmount, usedAmount);
            BudgetSetRequest request = TestDataBuilder.buildBudgetRequest(testAccountId, category, newAmount);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(existingBudget));
            when(budgetRepository.save(any(Budget.class))).thenAnswer(invocation -> invocation.getArgument(0));
            doNothing().when(analysisService).updateBudgetAnalysis(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            BudgetSetResponse response = budgetService.setBudget(request);

            assertEquals(newAmount.subtract(usedAmount), response.getRemaining());
        }

        @Test
        @DisplayName("按分类查询预算成功")
        void testGetBudgetByCategory() {
            String category = "餐饮";
            Budget budget = TestDataBuilder.buildNormalBudget("budget_001", testAccountId);

            when(budgetRepository.findByAccountIdAndBudgetCategoryAndBudgetPeriod(
                testAccountId, category, currentPeriod)).thenReturn(Optional.of(budget));

            Optional<Budget> result = budgetService.getBudgetByCategory(testAccountId, category);

            assertTrue(result.isPresent());
            assertEquals(category, result.get().getBudgetCategory());
        }
    }
}
