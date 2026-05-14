package com.finance.service;

import com.finance.FinanceBookApplication;
import com.finance.builder.TestDataBuilder;
import com.finance.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = FinanceBookApplication.class)
@Transactional
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Test
    void testCreateAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        assertNotNull(account);
        assertNotNull(account.getAccountId());
        assertEquals("测试账户", account.getAccountName());
        assertEquals("bank", account.getAccountType());
        assertEquals("active", account.getAccountStatus());
        assertEquals(BigDecimal.ZERO, account.getAccountBalance());
    }

    @Test
    void testGetAccountById() {
        Account created = accountService.createAccount("测试账户", "bank", "CNY");
        Account found = accountService.getAccountById(created.getAccountId());

        assertNotNull(found);
        assertEquals(created.getAccountId(), found.getAccountId());
    }

    @Test
    void testGetAllAccounts() {
        accountService.createAccount("账户1", "bank", "CNY");
        accountService.createAccount("账户2", "cash", "CNY");

        List<Account> accounts = accountService.getAllAccounts();
        assertTrue(accounts.size() >= 2);
    }

    @Test
    void testUpdateBalanceIncome() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        BigDecimal amount = new BigDecimal("5000");

        Account updated = accountService.updateBalance(account.getAccountId(), amount, true);

        assertEquals(amount, updated.getAccountBalance());
    }

    @Test
    void testUpdateBalanceExpense() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        accountService.updateBalance(account.getAccountId(), new BigDecimal("5000"), true);

        Account updated = accountService.updateBalance(account.getAccountId(), new BigDecimal("2000"), false);

        assertEquals(new BigDecimal("3000"), updated.getAccountBalance());
    }

    @Test
    void testFreezeAndActivateAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        Account frozen = accountService.freezeAccount(account.getAccountId());
        assertEquals("frozen", frozen.getAccountStatus());

        Account activated = accountService.activateAccount(account.getAccountId());
        assertEquals("active", activated.getAccountStatus());
    }

    @Test
    void testIsAccountActive() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        assertTrue(accountService.isAccountActive(account.getAccountId()));

        accountService.freezeAccount(account.getAccountId());
        assertFalse(accountService.isAccountActive(account.getAccountId()));
    }
}
