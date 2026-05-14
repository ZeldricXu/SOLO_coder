package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.Account;
import com.finance.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/create")
    public ApiResponse<Account> createAccount(@RequestBody Map<String, String> request) {
        Account account = accountService.createAccount(
                request.get("account_name"),
                request.get("account_type"),
                request.getOrDefault("account_currency", "CNY")
        );
        return ApiResponse.success(account);
    }

    @GetMapping("/{accountId}")
    public ApiResponse<Account> getAccount(@PathVariable String accountId) {
        Account account = accountService.getAccountById(accountId);
        return ApiResponse.success(account);
    }

    @GetMapping
    public ApiResponse<List<Account>> getAllAccounts() {
        List<Account> accounts = accountService.getAllAccounts();
        return ApiResponse.success(accounts);
    }

    @PutMapping("/{accountId}/freeze")
    public ApiResponse<Account> freezeAccount(@PathVariable String accountId) {
        Account account = accountService.freezeAccount(accountId);
        return ApiResponse.success(account);
    }

    @PutMapping("/{accountId}/activate")
    public ApiResponse<Account> activateAccount(@PathVariable String accountId) {
        Account account = accountService.activateAccount(accountId);
        return ApiResponse.success(account);
    }
}
