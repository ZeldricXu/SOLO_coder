package com.web3platform.crosschainbridge.exception;

public final class BridgeErrorCode {

    private BridgeErrorCode() {}

    public static final String LOCK_FAILED = "BRIDGE_001";
    public static final String MINT_FAILED = "BRIDGE_002";
    public static final String PROOF_VERIFICATION_FAILED = "BRIDGE_003";
    public static final String SIGNATURE_INVALID = "BRIDGE_004";
    public static final String SIGNING_FAILED = "BRIDGE_005";
    public static final String LOCK_NOT_FOUND = "BRIDGE_006";
    public static final String INVALID_LOCK_STATUS = "BRIDGE_007";
    public static final String AMOUNT_MISMATCH = "BRIDGE_008";
    public static final String MINT_ALREADY_EXISTS = "BRIDGE_009";
    public static final String ATOMICITY_VIOLATION = "BRIDGE_010";
    public static final String ROLLBACK_FAILED = "BRIDGE_011";
    public static final String INVALID_REQUEST = "BRIDGE_012";
    public static final String RPC_CONNECTION_FAILED = "BRIDGE_013";
    public static final String POOL_EXHAUSTED = "BRIDGE_014";
    public static final String MESSAGE_NOT_FOUND = "BRIDGE_015";
}
