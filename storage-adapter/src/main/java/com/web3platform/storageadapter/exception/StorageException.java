package com.web3platform.storageadapter.exception;

public class StorageException extends RuntimeException {

    private final String errorCode;
    private final String storageType;
    private final String cid;

    public StorageException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.storageType = null;
        this.cid = null;
    }

    public StorageException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.storageType = null;
        this.cid = null;
    }

    public StorageException(String errorCode, String storageType, String cid, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.storageType = storageType;
        this.cid = cid;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getStorageType() {
        return storageType;
    }

    public String getCid() {
        return cid;
    }
}
