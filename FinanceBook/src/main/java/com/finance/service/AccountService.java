package com.finance.service;

import com.finance.entity.Account;
import com.finance.exception.FinanceException;
import com.finance.repository.AccountRepository;
import com.finance.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountTypeService accountTypeService;

    @Transactional
    public Account createAccount(String accountName, String accountType, String accountCurrency) {
        if (!accountTypeService.existsByCode(accountType)) {
            throw new FinanceException(400, "无效的账户类型: " + accountType);
        }

        Account account = Account.builder()
                .accountId(IdGenerator.generateAccountId())
                .accountName(accountName)
                .accountType(accountType)
                .accountBalance(BigDecimal.ZERO)
                .accountStatus("active")
                .accountCurrency(accountCurrency != null ? accountCurrency : "CNY")
                .createdAt(LocalDateTime.now())
                .build();

        Account saved = accountRepository.save(account);
        log.info("创建账户成功: accountId={}", saved.getAccountId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Account getAccountById(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> FinanceException.accountNotFound(accountId));
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Account> getAccountsByStatus(String status) {
        return accountRepository.findByAccountStatus(status);
    }

    @Transactional
    public Account updateBalance(String accountId, BigDecimal amount, boolean isIncome) {
        Account account = getAccountById(accountId);

        if ("frozen".equals(account.getAccountStatus())) {
            throw FinanceException.accountFrozen(accountId);
        }

        BigDecimal newBalance;
        if (isIncome) {
            newBalance = account.getAccountBalance().add(amount);
        } else {
            newBalance = account.getAccountBalance().subtract(amount);
        }

        account.setAccountBalance(newBalance);
        account.setUpdatedAt(LocalDateTime.now());

        Account updated = accountRepository.save(account);
        log.info("更新账户余额: accountId={}, 原余额={}, 新余额={}", accountId,
                account.getAccountBalance().subtract(isIncome ? amount : amount.negate()), newBalance);
        return updated;
    }

    @Transactional
    public Account updateAccount(String accountId, String accountName, String accountStatus) {
        Account account = getAccountById(accountId);

        if (accountName != null && !accountName.isEmpty()) {
            account.setAccountName(accountName);
        }
        if (accountStatus != null && !accountStatus.isEmpty()) {
            account.setAccountStatus(accountStatus);
        }
        account.setUpdatedAt(LocalDateTime.now());

        return accountRepository.save(account);
    }

    @Transactional
    public Account freezeAccount(String accountId) {
        return updateAccount(accountId, null, "frozen");
    }

    @Transactional
    public Account activateAccount(String accountId) {
        return updateAccount(accountId, null, "active");
    }

    @Transactional(readOnly = true)
    public boolean isAccountActive(String accountId) {
        Account account = getAccountById(accountId);
        return "active".equals(account.getAccountStatus());
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountId) {
        Account account = getAccountById(accountId);
        return account.getAccountBalance();
    }
}
