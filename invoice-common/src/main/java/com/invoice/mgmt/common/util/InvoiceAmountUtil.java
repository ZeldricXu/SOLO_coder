package com.invoice.mgmt.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class InvoiceAmountUtil {
    public static BigDecimal calculateTax(BigDecimal amount, BigDecimal taxRate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) < 0 || taxRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("税率必须在0-1之间");
        }
        return amount.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateTotal(BigDecimal amount, BigDecimal tax) {
        if (amount == null || tax == null) {
            throw new IllegalArgumentException("金额和税额不能为空");
        }
        return amount.add(tax).setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean isValidAmount(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0
                && amount.compareTo(new BigDecimal("9999999999.99")) <= 0;
    }
}
