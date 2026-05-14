package com.schedulebook.exception;

public class BookingException extends RuntimeException {
    
    private final Integer code;
    
    public BookingException(String message) {
        super(message);
        this.code = 500;
    }
    
    public BookingException(Integer code, String message) {
        super(message);
        this.code = code;
    }
    
    public Integer getCode() {
        return code;
    }
}
