package com.movie.exception;

public class MovieException extends RuntimeException {

    private Integer code;

    public MovieException(String message) {
        super(message);
        this.code = 500;
    }

    public MovieException(Integer code, String message) {
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
