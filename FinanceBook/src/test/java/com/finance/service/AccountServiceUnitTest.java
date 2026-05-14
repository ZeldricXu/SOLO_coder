package com.finance.service;

import com.finance.builder.TestDataBuilder;
import com.finance.entity.Account;
import com.finance.entity.AccountType;
import com.finance.exception.FinanceException;
import com.finance.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("账户管理模块单元测试")
class AccountServiceUnitTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTypeService accountTypeService;

    @InjectMocks
    private AccountService accountService;

    private String testAccountId;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccountId = TestDataBuilder.generateUniqueId("account");
        testAccount = TestDataBuilder.buildActiveBankAccount(testAccountId);
    }

    @Nested
    @DisplayName("账户余额计算测试")
    class BalanceCalculationTests {

        @Test
        @DisplayName("收入增加余额计算正确")
        void testIncomeBalanceIncrease() {
            Account account = TestDataBuilder.buildZeroBalanceAccount(testAccountId);
            BigDecimal incomeAmount = new BigDecimal("5000.00");
            BigDecimal expectedBalance = new BigDecimal("5000.00");

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.updateBalance(testAccountId, incomeAmount, true);

            assertEquals(expectedBalance, result.getAccountBalance());
            verify(accountRepository).findById(testAccountId);
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("支出减少余额计算正确")
        void testExpenseBalanceDecrease() {
            Account account = TestDataBuilder.buildActiveBankAccount(testAccountId);
            BigDecimal initialBalance = account.getAccountBalance();
            BigDecimal expenseAmount = new BigDecimal("3000.00");
            BigDecimal expectedBalance = initialBalance.subtract(expenseAmount);

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.updateBalance(testAccountId, expenseAmount, false);

            assertEquals(expectedBalance, result.getAccountBalance());
            verify(accountRepository).findById(testAccountId);
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("连续收支余额计算正确")
        void testMultipleTransactionsBalance() {
            Account account = TestDataBuilder.buildZeroBalanceAccount(testAccountId);

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account saved = invocation.getArgument(0);
                account.setAccountBalance(saved.getAccountBalance());
                account.setUpdatedAt(saved.getUpdatedAt());
                return account;
            });

            accountService.updateBalance(testAccountId, new BigDecimal("10000.00"), true);
            accountService.updateBalance(testAccountId, new BigDecimal("3000.00"), false);
            accountService.updateBalance(testAccountId, new BigDecimal("500.00"), false);

            assertEquals(new BigDecimal("6500.00"), account.getAccountBalance());
            verify(accountRepository, times(3)).save(any(Account.class));
        }

        @Test
        @DisplayName("大额收入余额计算精度正确")
        void testLargeIncomePrecision() {
            Account account = TestDataBuilder.buildZeroBalanceAccount(testAccountId);
            BigDecimal largeAmount = new BigDecimal("999999999.99");

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.updateBalance(testAccountId, largeAmount, true);

            assertEquals(largeAmount, result.getAccountBalance());
        }

        @Test
        @DisplayName("小数精度余额计算正确")
        void testDecimalPrecisionBalance() {
            Account account = TestDataBuilder.buildZeroBalanceAccount(testAccountId);
            BigDecimal amount1 = new BigDecimal("100.50");
            BigDecimal amount2 = new BigDecimal("200.75");

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account saved = invocation.getArgument(0);
                account.setAccountBalance(saved.getAccountBalance());
                return account;
            });

            accountService.updateBalance(testAccountId, amount1, true);
            accountService.updateBalance(testAccountId, amount2, true);

            assertEquals(new BigDecimal("301.25"), account.getAccountBalance());
        }
    }

    @Nested
    @DisplayName("账户状态流转测试")
    class AccountStatusFlowTests {

        @Test
        @DisplayName("活跃账户冻结状态流转正确")
        void testActiveToFrozenStatus() {
            Account account = TestDataBuilder.buildActiveBankAccount(testAccountId);
            assertEquals("active", account.getAccountStatus());

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account frozenAccount = accountService.freezeAccount(testAccountId);

            assertEquals("frozen", frozenAccount.getAccountStatus());
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("冻结账户激活状态流转正确")
        void testFrozenToActiveStatus() {
            Account account = TestDataBuilder.buildFrozenAccount(testAccountId);
            assertEquals("frozen", account.getAccountStatus());

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account activatedAccount = accountService.activateAccount(testAccountId);

            assertEquals("active", activatedAccount.getAccountStatus());
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("活跃账户状态检测正确")
        void testActiveAccountStatusCheck() {
            Account account = TestDataBuilder.buildActiveBankAccount(testAccountId);

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));

            assertTrue(accountService.isAccountActive(testAccountId));
        }

        @Test
        @DisplayName("冻结账户状态检测正确")
        void testFrozenAccountStatusCheck() {
            Account account = TestDataBuilder.buildFrozenAccount(testAccountId);

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));

            assertFalse(accountService.isAccountActive(testAccountId));
        }

        @Test
        @DisplayName("冻结账户拒绝更新余额")
        void testFrozenAccountRejectBalanceUpdate() {
            Account account = TestDataBuilder.buildFrozenAccount(testAccountId);

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));

            assertThrows(FinanceException.class, () ->
                accountService.updateBalance(testAccountId, new BigDecimal("1000.00"), true)
            );

            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("账户类型动态加载测试")
    class AccountTypeLoadingTests {

        @Test
        @DisplayName("有效账户类型创建账户成功")
        void testCreateAccountWithValidType() {
            String accountName = "测试账户";
            String accountType = "bank";
            String currency = "CNY";

            when(accountTypeService.existsByCode(accountType)).thenReturn(true);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.createAccount(accountName, accountType, currency);

            assertNotNull(result);
            assertNotNull(result.getAccountId());
            assertEquals(accountName, result.getAccountName());
            assertEquals(accountType, result.getAccountType());
            assertEquals("active", result.getAccountStatus());
            assertEquals(BigDecimal.ZERO, result.getAccountBalance());
            verify(accountTypeService).existsByCode(accountType);
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("无效账户类型创建账户失败")
        void testCreateAccountWithInvalidType() {
            String invalidType = "invalid_type";

            when(accountTypeService.existsByCode(invalidType)).thenReturn(false);

            assertThrows(FinanceException.class, () ->
                accountService.createAccount("测试账户", invalidType, "CNY")
            );

            verify(accountRepository, never()).save(any(Account.class));
        }

        @Test
        @DisplayName("现金类型账户创建成功")
        void testCreateCashAccount() {
            String cashType = "cash";

            when(accountTypeService.existsByCode(cashType)).thenReturn(true);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.createAccount("现金账户", cashType, "CNY");

            assertEquals(cashType, result.getAccountType());
        }

        @Test
        @DisplayName("多种账户类型支持")
        void testMultipleAccountTypes() {
            String[] types = {"bank", "cash", "credit", "invest"};

            for (String type : types) {
                when(accountTypeService.existsByCode(type)).thenReturn(true);
                when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

                Account result = accountService.createAccount(type + "账户", type, "CNY");

                assertEquals(type, result.getAccountType());
                reset(accountTypeService, accountRepository);
            }
        }
    }

    @Nested
    @DisplayName("账户查询测试")
    class AccountQueryTests {

        @Test
        @DisplayName("查询存在账户成功")
        void testGetExistingAccount() {
            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));

            Account result = accountService.getAccountById(testAccountId);

            assertNotNull(result);
            assertEquals(testAccountId, result.getAccountId());
            verify(accountRepository).findById(testAccountId);
        }

        @Test
        @DisplayName("查询不存在账户抛出异常")
        void testGetNonExistingAccount() {
            String nonExistingId = "non_existing_id";

            when(accountRepository.findById(nonExistingId)).thenReturn(Optional.empty());

            assertThrows(FinanceException.class, () ->
                accountService.getAccountById(nonExistingId)
            );
        }

        @Test
        @DisplayName("查询所有账户成功")
        void testGetAllAccounts() {
            List<Account> accounts = Arrays.asList(
                TestDataBuilder.buildActiveBankAccount("account_1"),
                TestDataBuilder.buildCashAccount("account_2")
            );

            when(accountRepository.findAll()).thenReturn(accounts);

            List<Account> result = accountService.getAllAccounts();

            assertEquals(2, result.size());
            verify(accountRepository).findAll();
        }

        @Test
        @DisplayName("按状态查询账户成功")
        void testGetAccountsByStatus() {
            List<Account> activeAccounts = Arrays.asList(
                TestDataBuilder.buildActiveBankAccount("account_1"),
                TestDataBuilder.buildCashAccount("account_2")
            );

            when(accountRepository.findByAccountStatus("active")).thenReturn(activeAccounts);

            List<Account> result = accountService.getAccountsByStatus("active");

            assertEquals(2, result.size());
            result.forEach(account -> assertEquals("active", account.getAccountStatus()));
        }

        @Test
        @DisplayName("获取账户余额正确")
        void testGetAccountBalance() {
            BigDecimal expectedBalance = new BigDecimal("15000.00");
            Account account = TestDataBuilder.buildAccount(
                testAccountId, "测试账户", "bank", expectedBalance, "active"
            );

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(account));

            BigDecimal balance = accountService.getBalance(testAccountId);

            assertEquals(expectedBalance, balance);
        }
    }

    @Nested
    @DisplayName("账户更新测试")
    class AccountUpdateTests {

        @Test
        @DisplayName("更新账户名称成功")
        void testUpdateAccountName() {
            String newName = "更新后的账户名称";

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.updateAccount(testAccountId, newName, null);

            assertEquals(newName, result.getAccountName());
            assertEquals(testAccount.getAccountType(), result.getAccountType());
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("部分更新只修改指定字段")
        void testPartialUpdate() {
            String originalName = testAccount.getAccountName();
            String originalType = testAccount.getAccountType();
            BigDecimal originalBalance = testAccount.getAccountBalance();

            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account result = accountService.updateAccount(testAccountId, null, "frozen");

            assertEquals(originalName, result.getAccountName());
            assertEquals(originalType, result.getAccountType());
            assertEquals(originalBalance, result.getAccountBalance());
            assertEquals("frozen", result.getAccountStatus());
        }

        @Test
        @DisplayName("更新时间戳正确")
        void testUpdateTimestamp() {
            when(accountRepository.findById(testAccountId)).thenReturn(Optional.of(testAccount));
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Account before = testAccount;
            Account after = accountService.updateAccount(testAccountId, "新名称", null);

            assertNull(before.getUpdatedAt());
            assertNotNull(after.getUpdatedAt());
        }
    }
}
