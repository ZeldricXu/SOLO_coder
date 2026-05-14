package com.finance.service;

import com.finance.FinanceBookApplication;
import com.finance.builder.TestDataBuilder;
import com.finance.dto.BudgetSetResponse;
import com.finance.entity.Account;
import com.finance.entity.Budget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = FinanceBookApplication.class)
@Transactional
class BudgetServiceTest {

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RecordService recordService;

    @Test
    void testSetBudget() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        BigDecimal budgetAmount = new BigDecimal("2000");

        BudgetSetResponse response = budgetService.setBudget(
                TestDataBuilder.buildBudgetRequest(account.getAccountId(), "餐饮", budgetAmount));

        assertNotNull(response);
        assertNotNull(response.getBudget_id());
        assertEquals(budgetAmount, response.getRemaining());
    }

    @Test
    void testGetBudgetsByAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        budgetService.setBudget(TestDataBuilder.buildBudgetRequest(account.getAccountId(), "餐饮", new BigDecimal("2000")));
        budgetService.setBudget(TestDataBuilder.buildBudgetRequest(account.getAccountId(), "交通", new BigDecimal("500")));

        List<Budget> budgets = budgetService.getBudgetsByAccount(account.getAccountId());
        assertEquals(2, budgets.size());
    }

    @Test
    void testBudgetCheckAndUpdate() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        accountService.updateBalance(account.getAccountId(), new BigDecimal("10000"), true);

        budgetService.setBudget(TestDataBuilder.buildBudgetRequest(account.getAccountId(), "餐饮", new BigDecimal("500")));

        recordService.createRecord(TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), new BigDecimal("300")));

        Budget budget = budgetService.getBudgetByCategory(account.getAccountId(), "餐饮").orElse(null);
        assertNotNull(budget);
        assertEquals(new BigDecimal("300"), budget.getBudgetUsed());
        assertEquals(new BigDecimal("200"), budget.getBudgetRemaining());
    }

    @Test
    void testBudgetExceeded() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        accountService.updateBalance(account.getAccountId(), new BigDecimal("10000"), true);

        budgetService.setBudget(TestDataBuilder.buildBudgetRequest(account.getAccountId(), "餐饮", new BigDecimal("500")));

        recordService.createRecord(TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), new BigDecimal("600")));

        Budget budget = budgetService.getBudgetByCategory(account.getAccountId(), "餐饮").orElse(null);
        assertNotNull(budget);
        assertTrue(budget.getBudgetRemaining().compareTo(BigDecimal.ZERO) < 0);
    }
}
