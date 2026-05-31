package com.web3platform.storageadapter.exception;

public final class StorageErrorCode {

    private StorageErrorCode() {}

    public static final String UPLOAD_FAILED = "STORAGE_001";
    public static final String DOWNLOAD_FAILED = "STORAGE_002";
    public static final String PIN_FAILED = "STORAGE_003";
    public static final String UNPIN_FAILED = "STORAGE_004";
    public static final String GET_STATUS_FAILED = "STORAGE_005";
    public static final String UNSUPPORTED_STORAGE_TYPE = "STORAGE_006";
    public static final String INVALID_REQUEST = "STORAGE_007";
    public static final String SESSION_NOT_FOUND = "STORAGE_008";
    public static final String SESSION_ALREADY_COMPLETED = "STORAGE_009";
    public static final String INVALID_CHUNK_INDEX = "STORAGE_010";
    public static final String CHUNK_UPLOAD_INCOMPLETE = "STORAGE_011";
    public static final String PERSIST_FAILED = "STORAGE_012";
}
