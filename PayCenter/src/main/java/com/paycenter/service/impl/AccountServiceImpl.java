package com.paycenter.service.impl;

import com.paycenter.entity.Account;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.AccountRepository;
import com.paycenter.service.AccountService;
import com.paycenter.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class AccountServiceImpl implements AccountService {

    @Autowired
    private AccountRepository accountRepository;

    @Override
    @Transactional
    public Account createAccount(String merchantId) {
        Optional<Account> existing = accountRepository.findByMerchantId(merchantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        Account account = Account.builder()
                .accountId(IdGenerator.generateAccountId(merchantId))
                .merchantId(merchantId)
                .balance(BigDecimal.ZERO)
                .frozenAmount(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .build();
        
        return accountRepository.save(account);
    }

    @Override
    @Cacheable(value = "accounts", key = "#merchantId")
    public Optional<Account> getAccountByMerchantId(String merchantId) {
        return accountRepository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    @CacheEvict(value = "accounts", key = "#merchantId")
    public Account deposit(String merchantId, BigDecimal amount, String remark) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于0");
        }
        
        Account account = accountRepository.findByMerchantId(merchantId)
                .orElseGet(() -> createAccount(merchantId));
        
        account.setBalance(account.getBalance().add(amount));
        account.setAvailableBalance(account.getBalance().subtract(account.getFrozenAmount()));
        
        return accountRepository.save(account);
    }

    @Override
    @Transactional
    @CacheEvict(value = "accounts", key = "#merchantId")
    public Account withdraw(String merchantId, BigDecimal amount, String remark) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于0");
        }
        
        Account account = accountRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new BusinessException("账户不存在"));
        
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException("可用余额不足");
        }
        
        account.setBalance(account.getBalance().subtract(amount));
        account.setAvailableBalance(account.getBalance().subtract(account.getFrozenAmount()));
        
        return accountRepository.save(account);
    }

    @Override
    @Transactional
    @CacheEvict(value = "accounts", key = "#merchantId")
    public Account freezeAmount(String merchantId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于0");
        }
        
        Account account = accountRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new BusinessException("账户不存在"));
        
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException("可用余额不足");
        }
        
        account.setFrozenAmount(account.getFrozenAmount().add(amount));
        account.setAvailableBalance(account.getBalance().subtract(account.getFrozenAmount()));
        
        return accountRepository.save(account);
    }

    @Override
    @Transactional
    @CacheEvict(value = "accounts", key = "#merchantId")
    public Account unfreezeAmount(String merchantId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须大于0");
        }
        
        Account account = accountRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new BusinessException("账户不存在"));
        
        if (account.getFrozenAmount().compareTo(amount) < 0) {
            throw new BusinessException("冻结金额不足");
        }
        
        account.setFrozenAmount(account.getFrozenAmount().subtract(amount));
        account.setAvailableBalance(account.getBalance().subtract(account.getFrozenAmount()));
        
        return accountRepository.save(account);
    }
}
