package com.paycenter.service;

import com.paycenter.entity.Account;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountService {
    Account createAccount(String merchantId);
    Optional<Account> getAccountByMerchantId(String merchantId);
    Account deposit(String merchantId, BigDecimal amount, String remark);
    Account withdraw(String merchantId, BigDecimal amount, String remark);
    Account freezeAmount(String merchantId, BigDecimal amount);
    Account unfreezeAmount(String merchantId, BigDecimal amount);
}
