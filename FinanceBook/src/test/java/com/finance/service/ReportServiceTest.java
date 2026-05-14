package com.finance.service;

import com.finance.FinanceBookApplication;
import com.finance.builder.TestDataBuilder;
import com.finance.dto.ReportQueryRequest;
import com.finance.dto.ReportQueryResponse;
import com.finance.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = FinanceBookApplication.class)
@Transactional
class ReportServiceTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private RecordService recordService;

    @Test
    void testQueryReport() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("5000")));
        recordService.createRecord(TestDataBuilder.buildExpenseRecordRequest(account.getAccountId(), new BigDecimal("2000")));

        ReportQueryRequest request = ReportQueryRequest.builder()
                .account_id(account.getAccountId())
                .build();

        ReportQueryResponse response = reportService.queryReport(request);

        assertNotNull(response);
        assertNotNull(response.getReport());
        assertEquals(new BigDecimal("5000"), response.getReport().getIncome());
        assertEquals(new BigDecimal("2000"), response.getReport().getExpense());
        assertEquals(new BigDecimal("3000"), response.getReport().getBalance());
    }

    @Test
    void testGetReportsByAccount() {
        Account account = accountService.createAccount("测试账户", "bank", "CNY");

        recordService.createRecord(TestDataBuilder.buildIncomeRecordRequest(account.getAccountId(), new BigDecimal("5000")));

        assertNotNull(reportService.getReportsByAccount(account.getAccountId()));
    }
}
