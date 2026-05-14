package com.contractmgmt.exception;

public class ContractException extends RuntimeException {

    private Integer code;

    public ContractException(String message) {
        super(message);
        this.code = 400;
    }

    public ContractException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }
}
