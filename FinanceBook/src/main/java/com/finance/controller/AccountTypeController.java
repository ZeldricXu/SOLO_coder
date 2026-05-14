package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.AccountType;
import com.finance.service.AccountTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/account-types")
@RequiredArgsConstructor
public class AccountTypeController {

    private final AccountTypeService accountTypeService;

    @GetMapping
    public ApiResponse<List<AccountType>> getAllTypes() {
        List<AccountType> types = accountTypeService.getAllTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/active")
    public ApiResponse<List<AccountType>> getActiveTypes() {
        List<AccountType> types = accountTypeService.getActiveTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/{typeCode}")
    public ApiResponse<AccountType> getTypeByCode(@PathVariable String typeCode) {
        AccountType type = accountTypeService.getTypeByCode(typeCode);
        return ApiResponse.success(type);
    }
}
