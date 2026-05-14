package com.finance.service;

import com.finance.FinanceBookApplication;
import com.finance.builder.TestDataBuilder;
import com.finance.dto.RecordCreateResponse;
import com.finance.entity.Account;
import com.finance.entity.Record;
import com.finance.exception.FinanceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = FinanceBookApplication.class)
@Transactional
class RecordServiceTest {

    @Autowired
    private RecordService recordService;

    @Autowired
    private AccountService accountService;

    @Test
    void testCreateIncomeRecord() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        BigDecimal amount = new BigDecimal("5000");

        RecordCreateResponse response = recordService.createRecord(
                TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), amount));

        assertNotNull(response);
        assertNotNull(response.getRecord_id());
        assertEquals(amount, response.getBalance());
    }

    @Test
    void testCreateExpenseRecord() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        accountService.updateBalance(account.getAccountId(), new BigDecimal("10000"), true);

        BigDecimal expenseAmount = new BigDecimal("2000");
        RecordCreateResponse response = recordService.createRecord(
                TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), expenseAmount));

        assertNotNull(response);
        assertEquals(new BigDecimal("8000"), response.getBalance());
    }

    @Test
    void testGetRecordsByAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("1000")));
        recordService.createRecord(TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), new BigDecimal("500")));

        List<Record> records = recordService.getRecordsByAccount(account.getAccountId());
        assertEquals(2, records.size());
    }

    @Test
    void testRecordWithFrozenAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");
        accountService.freezeAccount(account.getAccountId());

        assertThrows(FinanceException.class, () ->
            recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("1000")))
        );
    }

    @Test
    void testRecordWithInvalidType() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        assertThrows(FinanceException.class, () ->
            recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("1000"))
                    .withRecord_type("invalid"))
        );
    }

    @Test
    void testCountRecords() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("1000")));
        recordService.createRecord(TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), new BigDecimal("500")));

        Long count = recordService.countRecords(account.getAccountId());
        assertEquals(2L, count);
    }
}
