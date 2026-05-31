package com.web3platform.storageadapter.constant;

public final class StorageConstants {

    private StorageConstants() {}

    public static final String PIN_STATUS_PINNED = "pinned";
    public static final String PIN_STATUS_UNPINNED = "unpinned";
    public static final String PIN_STATUS_PINNING = "pinning";
    public static final String PIN_STATUS_FAILED = "failed";

    public static final String CID_PREFIX_IPFS = "Qm";

    public static final int DEFAULT_STREAM_BUFFER_SIZE = 8192;
    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENT_UPLOADS = 5;

    public static final String IPFS_API_ADD = "/api/v0/add";
    public static final String IPFS_API_CAT = "/api/v0/cat?arg=";
    public static final String IPFS_API_PIN_ADD = "/api/v0/pin/add?arg=";
    public static final String IPFS_API_PIN_RM = "/api/v0/pin/rm?arg=";
    public static final String IPFS_API_PIN_LS = "/api/v0/pin/ls?arg=";

    public static final String ARWEAVE_TX_PATH = "/tx";
    public static final String ARWEAVE_TX_DATA_PATH = "/tx/%s/data";
    public static final String ARWEAVE_PIN_PATH = "/pin";
    public static final String ARWEAVE_PIN_DELETE_PATH = "/pin/%s";
}
