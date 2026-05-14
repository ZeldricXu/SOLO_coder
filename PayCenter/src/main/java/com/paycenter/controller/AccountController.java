package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.entity.Account;
import com.paycenter.service.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    @Autowired
    private AccountService accountService;

    @PostMapping("/{merchantId}")
    public ApiResponse<Account> createAccount(@PathVariable String merchantId) {
        Account account = accountService.createAccount(merchantId);
        return ApiResponse.success(account);
    }

    @GetMapping("/{merchantId}")
    public ApiResponse<Account> getAccount(@PathVariable String merchantId) {
        Optional<Account> account = accountService.getAccountByMerchantId(merchantId);
        return account.map(ApiResponse::success)
                .orElseGet(() -> {
                    Account newAccount = accountService.createAccount(merchantId);
                    return ApiResponse.success(newAccount);
                });
    }

    @PostMapping("/{merchantId}/deposit")
    public ApiResponse<Account> deposit(
            @PathVariable String merchantId,
            @RequestParam BigDecimal amount) {
        Account account = accountService.deposit(merchantId, amount, "手动充值");
        return ApiResponse.success(account);
    }

    @PostMapping("/{merchantId}/freeze")
    public ApiResponse<Account> freeze(
            @PathVariable String merchantId,
            @RequestParam BigDecimal amount) {
        Account account = accountService.freezeAmount(merchantId, amount);
        return ApiResponse.success(account);
    }

    @PostMapping("/{merchantId}/unfreeze")
    public ApiResponse<Account> unfreeze(
            @PathVariable String merchantId,
            @RequestParam BigDecimal amount) {
        Account account = accountService.unfreezeAmount(merchantId, amount);
        return ApiResponse.success(account);
    }
}
