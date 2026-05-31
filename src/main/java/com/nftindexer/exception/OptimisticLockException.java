package com.nftindexer.exception;

public class OptimisticLockException extends BusinessException {

    public OptimisticLockException(String message) {
        super(409, message);
    }
}
