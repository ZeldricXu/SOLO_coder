package com.assetinventory.exception;

public class InventoryException extends RuntimeException {

    private final int code;

    public InventoryException(String message) {
        super(message);
        this.code = 500;
    }

    public InventoryException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
