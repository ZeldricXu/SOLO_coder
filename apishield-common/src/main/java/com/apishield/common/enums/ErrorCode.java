package com.apishield.common.enums;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS("200", "成功"),
    BAD_REQUEST("400", "请求参数错误"),
    UNAUTHORIZED("401", "未授权"),
    FORBIDDEN("403", "禁止访问"),
    NOT_FOUND("404", "资源不存在"),
    INTERNAL_ERROR("500", "内部服务器错误"),
    VALIDATION_FAILED("422", "参数校验失败"),

    SHAMIR_GENERATE_FAILED("SHAMIR_001", "密钥分片生成失败"),
    SHAMIR_RECOVER_FAILED("SHAMIR_002", "密钥恢复失败"),
    SHAMIR_THRESHOLD_NOT_MET("SHAMIR_003", "分片数量未达到阈值"),

    AUDIT_HASH_CHAIN_BROKEN("AUDIT_001", "审计日志哈希链断裂"),
    AUDIT_VERIFY_FAILED("AUDIT_002", "审计日志完整性验证失败"),

    MPC_PROTOCOL_ERROR("MPC_001", "MPC协议执行错误"),
    MPC_PARTICIPANT_TIMEOUT("MPC_002", "参与方响应超时"),

    FL_TRAINING_FAILED("FL_001", "联邦学习训练失败"),
    FL_AGGREGATION_ERROR("FL_002", "梯度聚合错误"),

    CLASSIFICATION_SCAN_ERROR("CLASSIFY_001", "数据扫描错误"),
    CLASSIFICATION_POLICY_ERROR("CLASSIFY_002", "分类策略应用错误"),

    TEE_ENCLAVE_ERROR("TEE_001", "TEE Enclave错误"),
    TEE_ATTESTATION_FAILED("TEE_002", "远程证明失败"),

    MASKING_POLICY_NOT_FOUND("MASK_001", "脱敏策略不存在"),
    MASKING_PERMISSION_DENIED("MASK_002", "无权限访问原始数据"),

    DP_BUDGET_EXHAUSTED("DP_001", "隐私预算耗尽"),
    DP_NOISE_GENERATION_ERROR("DP_002", "噪声生成失败");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
