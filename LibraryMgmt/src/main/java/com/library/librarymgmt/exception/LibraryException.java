package com.library.librarymgmt.exception;

public class LibraryException extends RuntimeException {
    private final int code;

    public LibraryException(int code, String message) {
        super(message);
        this.code = code;
    }

    public LibraryException(String message) {
        super(message);
        this.code = 400;
    }

    public int getCode() {
        return code;
    }
}
