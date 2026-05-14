package com.invoice.mgmt.common.exception;

import lombok.Getter;

@Getter
public class InvoiceException extends RuntimeException {
    private final int code;
    private final String message;

    public InvoiceException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public InvoiceException(String message) {
        super(message);
        this.code = 500;
        this.message = message;
    }

    public static InvoiceException invalidType() {
        return new InvoiceException(400, "发票类型无效");
    }

    public static InvoiceException missingBuyerInfo() {
        return new InvoiceException(400, "购买方信息缺失");
    }

    public static InvoiceException invalidAmount() {
        return new InvoiceException(400, "发票金额异常");
    }

    public static InvoiceException numberInsufficient() {
        return new InvoiceException(500, "发票号码不足");
    }

    public static InvoiceException notFound() {
        return new InvoiceException(404, "发票不存在");
    }

    public static InvoiceException verifyTimeout() {
        return new InvoiceException(504, "验证超时");
    }

    public static InvoiceException verifyFailed() {
        return new InvoiceException(400, "验证失败");
    }

    public static InvoiceException invalidStatus() {
        return new InvoiceException(400, "发票状态无效");
    }

    public static InvoiceException alreadyReimbursed() {
        return new InvoiceException(400, "发票已报销");
    }
}
