package com.houserental.exception;

import lombok.Getter;

@Getter
public class HouseRentalException extends RuntimeException {
    private final int code;

    public HouseRentalException(int code, String message) {
        super(message);
        this.code = code;
    }

    public HouseRentalException(String message) {
        super(message);
        this.code = 500;
    }
}
