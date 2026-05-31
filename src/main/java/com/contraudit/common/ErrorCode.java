package com.contraudit.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "not found"),
    VALIDATION_ERROR(422, "validation error"),
    INTERNAL_ERROR(500, "internal server error"),
    GATEWAY_TIMEOUT(504, "gateway timeout"),

    WALLET_NOT_FOUND(10001, "wallet not found"),
    WALLET_CREATE_FAILED(10002, "wallet creation failed"),
    ADDRESS_DERIVE_FAILED(10003, "address derivation failed"),
    ADDRESS_NOT_FOUND(10004, "address not found"),

    MULTISIG_WALLET_NOT_FOUND(20001, "multisig wallet not found"),
    MULTISIG_PROPOSAL_NOT_FOUND(20002, "multisig proposal not found"),
    MULTISIG_PROPOSAL_EXPIRED(20003, "multisig proposal expired"),
    MULTISIG_INSUFFICIENT_CONFIRMATIONS(20004, "insufficient confirmations"),
    MULTISIG_ALREADY_APPROVED(20005, "already approved"),
    MULTISIG_INVALID_SIGNER(20006, "invalid signer"),
    MULTISIG_EXECUTION_FAILED(20007, "proposal execution failed"),

    BRIDGE_TRANSFER_NOT_FOUND(30001, "bridge transfer not found"),
    BRIDGE_INVALID_MESSAGE(30002, "invalid bridge message"),
    BRIDGE_VERIFICATION_FAILED(30003, "bridge verification failed"),
    BRIDGE_CHAIN_NOT_SUPPORTED(30004, "chain not supported"),

    TX_CONSTRUCTION_FAILED(40001, "transaction construction failed"),
    TX_SIGNING_FAILED(40002, "transaction signing failed"),
    TX_BROADCAST_FAILED(40003, "transaction broadcast failed"),
    TX_NONCE_TOO_LOW(40004, "nonce too low"),
    INSUFFICIENT_BALANCE(40005, "insufficient balance"),

    STORAGE_UPLOAD_FAILED(50001, "storage upload failed"),
    STORAGE_PIN_FAILED(50002, "storage pin failed"),
    STORAGE_NOT_FOUND(50003, "storage content not found"),
    STORAGE_TYPE_NOT_SUPPORTED(50004, "storage type not supported"),

    ZKP_VERIFICATION_FAILED(60001, "zkp verification failed"),
    ZKP_CIRCUIT_NOT_FOUND(60002, "zkp circuit not found"),
    ZKP_INVALID_PROOF(60003, "invalid zkp proof"),

    EVENT_LISTENER_NOT_FOUND(70001, "event listener not found"),
    EVENT_LISTENER_START_FAILED(70002, "event listener start failed"),

    GAS_ESTIMATION_FAILED(80001, "gas estimation failed"),
    GAS_ORACLE_NOT_AVAILABLE(80002, "gas oracle not available");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
