package com.finance.service;

import com.finance.builder.TestDataBuilder;
import com.finance.dto.RecordCreateRequest;
import com.finance.dto.RecordCreateResponse;
import com.finance.entity.Account;
import com.finance.entity.Category;
import com.finance.entity.Record;
import com.finance.exception.FinanceException;
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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("收支记录模块单元测试")
class RecordServiceUnitTest {

    @Mock
    private RecordRepository recordRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private BudgetService budgetService;

    @Mock
    private ReportService reportService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private RecordService recordService;

    private String testAccountId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccountId = TestDataBuilder.generateUniqueId("account");
        testAccount = TestDataBuilder.buildActiveBankAccount(testAccountId);
    }

    @Nested
    @DisplayName("记录数据有效性校验测试")
    class RecordValidationTests {

        @Test
        @DisplayName("有效收入记录创建成功")
        void testValidIncomeRecord() {
            BigDecimal amount = new BigDecimal("5000.00");
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, amount);
            Account updatedAccount = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", amount, "active"
            );

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory("income", "工资")).thenReturn(
                TestDataBuilder.buildIncomeCategory("cat_001", "工资")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, amount, true)).thenReturn(updatedAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            assertNotNull(response.getRecord_id());
            assertEquals(amount, response.getBalance());
            verify(recordRepository).save(any(Record.class));
            verify(accountService).updateBalance(testAccountId, amount, true);
        }

        @Test
        @DisplayName("有效支出记录创建成功")
        void testValidExpenseRecord() {
            BigDecimal initialBalance = new BigDecimal("10000.00");
            BigDecimal expenseAmount = new BigDecimal("3000.00");
            BigDecimal expectedBalance = initialBalance.subtract(expenseAmount);

            Account account = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", initialBalance, "active"
            );
            Account updatedAccount = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", expectedBalance, "active"
            );
            RecordCreateRequest request = TestDataBuilder.buildExpenseRecordRequest(testAccountId, expenseAmount);

            when(accountService.getAccountById(testAccountId)).thenReturn(account);
            when(categoryService.matchCategory("expense", "餐饮")).thenReturn(
                TestDataBuilder.buildExpenseCategory("cat_002", "餐饮")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, expenseAmount, false)).thenReturn(updatedAccount);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            assertEquals(expectedBalance, response.getBalance());
        }

        @Test
        @DisplayName("账户不存在时拒绝记录")
        void testRejectNonExistingAccount() {
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest("non_existing", new BigDecimal("1000.00"));

            when(accountService.getAccountById("non_existing"))
                .thenThrow(FinanceException.accountNotFound("non_existing"));

            assertThrows(FinanceException.class, () -> recordService.createRecord(request));
            verify(recordRepository, never()).save(any(Record.class));
        }

        @Test
        @DisplayName("账户已冻结时拒绝记录")
        void testRejectFrozenAccount() {
            Account frozenAccount = TestDataBuilder.buildFrozenAccount(testAccountId);
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, new BigDecimal("1000.00"));

            when(accountService.getAccountById(testAccountId)).thenReturn(frozenAccount);

            assertThrows(FinanceException.class, () -> recordService.createRecord(request));
            verify(recordRepository, never()).save(any(Record.class));
        }
    }

    @Nested
    @DisplayName("不同收支类型校验规则差异测试")
    class RecordTypeValidationTests {

        @Test
        @DisplayName("收入记录关联来源分类校验成功")
        void testIncomeRecordWithSourceCategory() {
            BigDecimal amount = new BigDecimal("8000.00");
            Category salaryCategory = TestDataBuilder.buildIncomeCategory("cat_001", "工资");
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, amount, "工资");
            Account updatedAccount = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", amount, "active"
            );

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory("income", "工资")).thenReturn(salaryCategory);
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, amount, true)).thenReturn(updatedAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            assertEquals(amount, response.getBalance());
            verify(categoryService).matchCategory("income", "工资");
        }

        @Test
        @DisplayName("支出记录关联支出分类校验成功")
        void testExpenseRecordWithExpenseCategory() {
            BigDecimal initialBalance = new BigDecimal("10000.00");
            BigDecimal expenseAmount = new BigDecimal("500.00");
            BigDecimal expectedBalance = initialBalance.subtract(expenseAmount);

            Account account = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", initialBalance, "active"
            );
            Account updatedAccount = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", expectedBalance, "active"
            );
            Category foodCategory = TestDataBuilder.buildExpenseCategory("cat_002", "餐饮");
            RecordCreateRequest request = TestDataBuilder.buildExpenseRecordRequest(testAccountId, expenseAmount, "餐饮");

            when(accountService.getAccountById(testAccountId)).thenReturn(account);
            when(categoryService.matchCategory("expense", "餐饮")).thenReturn(foodCategory);
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, expenseAmount, false)).thenReturn(updatedAccount);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            assertEquals(expectedBalance, response.getBalance());
            verify(categoryService).matchCategory("expense", "餐饮");
        }

        @Test
        @DisplayName("收入类型与支出分类不匹配时使用原始分类")
        void testIncomeWithExpenseCategory() {
            BigDecimal amount = new BigDecimal("5000.00");
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, amount, "餐饮");
            Account updatedAccount = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", amount, "active"
            );

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory("income", "餐饮")).thenReturn(null);
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, amount, true)).thenReturn(updatedAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            verify(categoryService).matchCategory("income", "餐饮");
        }

        @Test
        @DisplayName("支出记录触发预算检查，收入记录不触发")
        void testExpenseTriggersBudgetCheck() {
            BigDecimal expenseAmount = new BigDecimal("1000.00");
            Account account = TestDataBuilder.buildActiveBankAccount(testAccountId);
            RecordCreateRequest expenseRequest = TestDataBuilder.buildExpenseRecordRequest(testAccountId, expenseAmount);

            when(accountService.getAccountById(testAccountId)).thenReturn(account);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildExpenseCategory("cat_001", "餐饮")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(account);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            recordService.createRecord(expenseRequest);

            verify(budgetService).checkAndUpdateBudget(testAccountId, "餐饮", expenseAmount);
        }

        @Test
        @DisplayName("收入记录不触发预算检查")
        void testIncomeDoesNotTriggerBudgetCheck() {
            BigDecimal incomeAmount = new BigDecimal("5000.00");
            RecordCreateRequest incomeRequest = TestDataBuilder.buildIncomeRecordRequest(testAccountId, incomeAmount);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildIncomeCategory("cat_001", "工资")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            recordService.createRecord(incomeRequest);

            verify(budgetService, never()).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
        }
    }

    @Nested
    @DisplayName("金额异常处理测试")
    class AmountExceptionTests {

        @Test
        @DisplayName("无效收支类型抛出异常")
        void testInvalidRecordTypeThrowsException() {
            RecordCreateRequest request = TestDataBuilder.buildInvalidTypeRecordRequest(testAccountId);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);

            assertThrows(FinanceException.class, () -> recordService.createRecord(request));
            verify(recordRepository, never()).save(any(Record.class));
        }

        @Test
        @DisplayName("收入类型校验成功")
        void testIncomeTypeValidation() {
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, new BigDecimal("1000.00"));

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildIncomeCategory("cat_001", "工资")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
            assertDoesNotThrow(() -> recordService.createRecord(request));
        }

        @Test
        @DisplayName("支出类型校验成功")
        void testExpenseTypeValidation() {
            RecordCreateRequest request = TestDataBuilder.buildExpenseRecordRequest(testAccountId, new BigDecimal("1000.00"));

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildExpenseCategory("cat_001", "餐饮")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response);
        }

        @Test
        @DisplayName("多种无效类型均被拒绝")
        void testAllInvalidTypesRejected() {
            String[] invalidTypes = {"transfer", "investment", "invalid", "", null};

            for (String invalidType : invalidTypes) {
                if (invalidType == null) continue;
                RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, new BigDecimal("1000.00"))
                    .withRecord_type(invalidType);

                when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);

                assertThrows(FinanceException.class, () -> recordService.createRecord(request));
                reset(recordRepository);
            }
        }
    }

    @Nested
    @DisplayName("记录查询测试")
    class RecordQueryTests {

        @Test
        @DisplayName("按ID查询记录成功")
        void testGetRecordById() {
            String recordId = TestDataBuilder.generateUniqueId("record");
            Record record = TestDataBuilder.buildIncomeRecord(recordId, testAccountId, new BigDecimal("5000.00"));

            when(recordRepository.findById(recordId)).thenReturn(Optional.of(record));

            Record result = recordService.getRecordById(recordId);

            assertNotNull(result);
            assertEquals(recordId, result.getRecordId());
        }

        @Test
        @DisplayName("查询不存在记录抛出异常")
        void testGetNonExistingRecord() {
            String nonExistingId = "non_existing";

            when(recordRepository.findById(nonExistingId)).thenReturn(Optional.empty());

            assertThrows(FinanceException.class, () -> recordService.getRecordById(nonExistingId));
        }

        @Test
        @DisplayName("按账户查询记录成功")
        void testGetRecordsByAccount() {
            List<Record> records = Arrays.asList(
                TestDataBuilder.buildIncomeRecord("rec_001", testAccountId, new BigDecimal("5000.00")),
                TestDataBuilder.buildExpenseRecord("rec_002", testAccountId, new BigDecimal("1000.00"))
            );

            when(recordRepository.findByAccountIdOrderByRecordTimeDesc(testAccountId)).thenReturn(records);

            List<Record> result = recordService.getRecordsByAccount(testAccountId);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("按类型查询记录成功")
        void testGetRecordsByType() {
            List<Record> incomeRecords = Arrays.asList(
                TestDataBuilder.buildIncomeRecord("rec_001", testAccountId, new BigDecimal("5000.00")),
                TestDataBuilder.buildIncomeRecord("rec_002", testAccountId, new BigDecimal("3000.00"))
            );

            when(recordRepository.findByAccountIdAndRecordType(testAccountId, "income")).thenReturn(incomeRecords);

            List<Record> result = recordService.getRecordsByAccountAndType(testAccountId, "income");

            assertEquals(2, result.size());
            result.forEach(r -> assertEquals("income", r.getRecordType()));
        }

        @Test
        @DisplayName("统计记录数量正确")
        void testCountRecords() {
            when(recordRepository.countByAccountId(testAccountId)).thenReturn(5L);

            Long count = recordService.countRecords(testAccountId);

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("按分类查询记录成功")
        void testGetRecordsByCategory() {
            List<Record> foodRecords = Arrays.asList(
                TestDataBuilder.buildExpenseRecord("rec_001", testAccountId, new BigDecimal("100.00")),
                TestDataBuilder.buildExpenseRecord("rec_002", testAccountId, new BigDecimal("200.00"))
            );

            when(recordRepository.findByAccountIdAndRecordCategory(testAccountId, "餐饮")).thenReturn(foodRecords);

            List<Record> result = recordService.getRecordsByCategory(testAccountId, "餐饮");

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("记录创建完整流程测试")
    class RecordCreationFlowTests {

        @Test
        @DisplayName("记录创建后更新报表")
        void testRecordCreationUpdatesReport() {
            BigDecimal amount = new BigDecimal("5000.00");
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, amount);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildIncomeCategory("cat_001", "工资")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            recordService.createRecord(request);

            verify(reportService).updateReport(eq(testAccountId), eq("income"), eq(amount), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("记录创建后更新分析数据")
        void testRecordCreationUpdatesAnalysis() {
            BigDecimal amount = new BigDecimal("3000.00");
            RecordCreateRequest request = TestDataBuilder.buildExpenseRecordRequest(testAccountId, amount, "餐饮");

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildExpenseCategory("cat_001", "餐饮")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            recordService.createRecord(request);

            verify(analysisService).updateAnalysis(eq(testAccountId), eq("expense"), eq(amount), eq("餐饮"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("记录创建后记录历史")
        void testRecordCreationRecordsHistory() {
            BigDecimal amount = new BigDecimal("1000.00");
            RecordCreateRequest request = TestDataBuilder.buildExpenseRecordRequest(testAccountId, amount);

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory(anyString(), anyString())).thenReturn(
                TestDataBuilder.buildExpenseCategory("cat_001", "餐饮")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(anyString(), any(BigDecimal.class), anyBoolean())).thenReturn(testAccount);
            doNothing().when(budgetService).checkAndUpdateBudget(anyString(), anyString(), any(BigDecimal.class));
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            recordService.createRecord(request);

            verify(historyService).recordHistory(eq(testAccountId), eq("record_create"), anyString());
        }

        @Test
        @DisplayName("完整收入记录创建流程验证")
        void testCompleteIncomeRecordFlow() {
            BigDecimal amount = new BigDecimal("8000.00");
            RecordCreateRequest request = TestDataBuilder.buildIncomeRecordRequest(testAccountId, amount, "工资");

            when(accountService.getAccountById(testAccountId)).thenReturn(testAccount);
            when(categoryService.matchCategory("income", "工资")).thenReturn(
                TestDataBuilder.buildIncomeCategory("cat_001", "工资")
            );
            when(recordRepository.save(any(Record.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(accountService.updateBalance(testAccountId, amount, true)).thenReturn(testAccount);
            doNothing().when(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            doNothing().when(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            doNothing().when(historyService).recordHistory(anyString(), anyString(), anyString());

            RecordCreateResponse response = recordService.createRecord(request);

            assertNotNull(response.getRecord_id());
            verify(accountService).getAccountById(testAccountId);
            verify(categoryService).matchCategory("income", "工资");
            verify(recordRepository).save(any(Record.class));
            verify(accountService).updateBalance(testAccountId, amount, true);
            verify(reportService).updateReport(anyString(), anyString(), any(BigDecimal.class), any(LocalDateTime.class));
            verify(analysisService).updateAnalysis(anyString(), anyString(), any(BigDecimal.class), anyString(), any(LocalDateTime.class));
            verify(historyService).recordHistory(anyString(), anyString(), anyString());
        }
    }
}
