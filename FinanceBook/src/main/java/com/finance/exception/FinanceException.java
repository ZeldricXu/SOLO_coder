package com.finance.exception;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class FinanceException extends RuntimeException {
    private final Integer code;
    private final String message;

    public FinanceException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public static FinanceException accountNotFound(String accountId) {
        return new FinanceException(404, "账户不存在: " + accountId);
    }

    public static FinanceException accountFrozen(String accountId) {
        return new FinanceException(403, "账户已冻结: " + accountId);
    }

    public static FinanceException categoryNotFound(String category) {
        return new FinanceException(404, "分类不存在: " + category);
    }

    public static FinanceException budgetExceeded(String category, BigDecimal amount, BigDecimal budget) {
        return new FinanceException(400, "预算超限: 分类=" + category + ", 金额=" + amount + ", 预算=" + budget);
    }

    public static FinanceException invalidRecordType(String recordType) {
        return new FinanceException(400, "无效的收支类型: " + recordType);
    }

    public static FinanceException insufficientBalance(BigDecimal required, BigDecimal available) {
        return new FinanceException(400, "余额不足: 所需=" + required + ", 可用=" + available);
    }
}
