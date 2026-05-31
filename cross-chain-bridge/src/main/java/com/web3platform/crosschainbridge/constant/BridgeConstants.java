package com.web3platform.crosschainbridge.constant;

public final class BridgeConstants {

    private BridgeConstants() {}

    public static final String LOCK_STATUS_PENDING = "PENDING";
    public static final String LOCK_STATUS_CONFIRMED = "CONFIRMED";
    public static final String LOCK_STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String LOCK_STATUS_FAILED = "FAILED";

    public static final String MINT_STATUS_PENDING = "PENDING";
    public static final String MINT_STATUS_CONFIRMED = "CONFIRMED";
    public static final String MINT_STATUS_FAILED = "FAILED";

    public static final int SIGNATURE_LENGTH = 65;
    public static final int HASH_LENGTH = 32;

    public static final int BRANCH_NODE_LENGTH = 17;
    public static final int EXTENSION_NODE_LENGTH = 2;
    public static final int LEAF_NODE_LENGTH = 2;

    public static final byte PREFIX_EVEN = 0x00;
    public static final byte PREFIX_ODD = 0x01;
    public static final byte PREFIX_EXTENSION_EVEN = 0x02;
    public static final byte PREFIX_EXTENSION_ODD = 0x03;

    public static final String PROOF_SIBLINGS_KEY = "siblings";
    public static final String PROOF_ROOT_KEY = "root";
    public static final String PROOF_INDEX_KEY = "index";

    public static final String HEX_PREFIX = "0x";

    public static final String[] EVM_CHAINS = {
            "ethereum", "bsc", "polygon", "arbitrum", "optimism",
            "avalanche", "fantom", "klaytn", "celo", "base"
    };

    public static final long DEFAULT_TX_CONFIRMATIONS = 1;
}
