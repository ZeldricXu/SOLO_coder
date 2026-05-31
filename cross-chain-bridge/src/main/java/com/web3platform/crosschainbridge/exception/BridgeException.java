package com.web3platform.crosschainbridge.exception;

public class BridgeException extends RuntimeException {

    private final String errorCode;
    private final String sourceChain;
    private final String targetChain;

    public BridgeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.sourceChain = null;
        this.targetChain = null;
    }

    public BridgeException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.sourceChain = null;
        this.targetChain = null;
    }

    public BridgeException(String errorCode, String sourceChain, String targetChain,
                           String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.sourceChain = sourceChain;
        this.targetChain = targetChain;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getSourceChain() {
        return sourceChain;
    }

    public String getTargetChain() {
        return targetChain;
    }
}
