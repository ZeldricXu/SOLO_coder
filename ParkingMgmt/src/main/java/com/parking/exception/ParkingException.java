package com.parking.exception;

public class ParkingException extends RuntimeException {
    private final int errorCode;

    public ParkingException(String message) {
        super(message);
        this.errorCode = 500;
    }

    public ParkingException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
