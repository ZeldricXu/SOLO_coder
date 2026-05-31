"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __exportStar = (this && this.__exportStar) || function(m, exports) {
    for (var p in m) if (p !== "default" && !Object.prototype.hasOwnProperty.call(exports, p)) __createBinding(exports, m, p);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MODULE_NAMES = exports.MODULES = exports.DifferentialPrivacyInjection = exports.DataClassification = exports.KeyShardingManagement = exports.FederatedLearningCoordinator = exports.SecureMultipartyComputation = exports.TrustedExecutionEnvironment = exports.AuditLogTamperProtection = exports.DynamicDataMasking = void 0;
exports.getModuleDescription = getModuleDescription;
exports.createZeroTrustSecurity = createZeroTrustSecurity;
__exportStar(require("./types"), exports);
__exportStar(require("./utils"), exports);
const dynamic_data_masking_1 = require("./modules/dynamic-data-masking");
Object.defineProperty(exports, "DynamicDataMasking", { enumerable: true, get: function () { return dynamic_data_masking_1.DynamicDataMasking; } });
const audit_log_tamper_protection_1 = require("./modules/audit-log-tamper-protection");
Object.defineProperty(exports, "AuditLogTamperProtection", { enumerable: true, get: function () { return audit_log_tamper_protection_1.AuditLogTamperProtection; } });
const trusted_execution_environment_1 = require("./modules/trusted-execution-environment");
Object.defineProperty(exports, "TrustedExecutionEnvironment", { enumerable: true, get: function () { return trusted_execution_environment_1.TrustedExecutionEnvironment; } });
const secure_multiparty_computation_1 = require("./modules/secure-multiparty-computation");
Object.defineProperty(exports, "SecureMultipartyComputation", { enumerable: true, get: function () { return secure_multiparty_computation_1.SecureMultipartyComputation; } });
const federated_learning_coordinator_1 = require("./modules/federated-learning-coordinator");
Object.defineProperty(exports, "FederatedLearningCoordinator", { enumerable: true, get: function () { return federated_learning_coordinator_1.FederatedLearningCoordinator; } });
const key_sharding_management_1 = require("./modules/key-sharding-management");
Object.defineProperty(exports, "KeyShardingManagement", { enumerable: true, get: function () { return key_sharding_management_1.KeyShardingManagement; } });
const data_classification_1 = require("./modules/data-classification");
Object.defineProperty(exports, "DataClassification", { enumerable: true, get: function () { return data_classification_1.DataClassification; } });
const differential_privacy_injection_1 = require("./modules/differential-privacy-injection");
Object.defineProperty(exports, "DifferentialPrivacyInjection", { enumerable: true, get: function () { return differential_privacy_injection_1.DifferentialPrivacyInjection; } });
exports.MODULES = {
    DynamicDataMasking: dynamic_data_masking_1.DynamicDataMasking,
    AuditLogTamperProtection: audit_log_tamper_protection_1.AuditLogTamperProtection,
    TrustedExecutionEnvironment: trusted_execution_environment_1.TrustedExecutionEnvironment,
    SecureMultipartyComputation: secure_multiparty_computation_1.SecureMultipartyComputation,
    FederatedLearningCoordinator: federated_learning_coordinator_1.FederatedLearningCoordinator,
    KeyShardingManagement: key_sharding_management_1.KeyShardingManagement,
    DataClassification: data_classification_1.DataClassification,
    DifferentialPrivacyInjection: differential_privacy_injection_1.DifferentialPrivacyInjection,
};
exports.MODULE_NAMES = {
    DynamicDataMasking: 'Dynamic Data Masking',
    AuditLogTamperProtection: 'Audit Log Tamper Protection',
    TrustedExecutionEnvironment: 'Trusted Execution Environment',
    SecureMultipartyComputation: 'Secure Multi-Party Computation',
    FederatedLearningCoordinator: 'Federated Learning Coordinator',
    KeyShardingManagement: 'Key Sharding Management',
    DataClassification: 'Data Classification',
    DifferentialPrivacyInjection: 'Differential Privacy Injection',
};
function getModuleDescription(moduleName) {
    const descriptions = {
        DynamicDataMasking: '基于用户权限动态脱敏敏感字段，保持数据可用性',
        AuditLogTamperProtection: '操作日志哈希链存储，篡改检测与完整性验证',
        TrustedExecutionEnvironment: 'TEE enclave管理，安全认证与远程证明',
        SecureMultipartyComputation: 'MPC协议执行协调，参与方输入加密与结果解密',
        FederatedLearningCoordinator: '训练任务分发、梯度加密聚合与全局模型更新',
        KeyShardingManagement: 'Shamir密钥分片生成、分发与阈值恢复',
        DataClassification: '自动扫描识别敏感数据，按分类等级应用策略',
        DifferentialPrivacyInjection: '对查询结果添加校准噪声，实现隐私预算管理',
    };
    return descriptions[moduleName];
}
function createZeroTrustSecurity() {
    return {
        dynamicDataMasking: (config) => new dynamic_data_masking_1.DynamicDataMasking(config),
        auditLogTamperProtection: (config) => new audit_log_tamper_protection_1.AuditLogTamperProtection(config),
        trustedExecutionEnvironment: (knownMeasurements) => new trusted_execution_environment_1.TrustedExecutionEnvironment(knownMeasurements),
        secureMultipartyComputation: () => new secure_multiparty_computation_1.SecureMultipartyComputation(),
        federatedLearningCoordinator: (masterSecret) => new federated_learning_coordinator_1.FederatedLearningCoordinator(masterSecret),
        keyShardingManagement: () => new key_sharding_management_1.KeyShardingManagement(),
        dataClassification: (config) => new data_classification_1.DataClassification(config),
        differentialPrivacyInjection: (defaultBudget) => new differential_privacy_injection_1.DifferentialPrivacyInjection(defaultBudget),
    };
}
exports.default = createZeroTrustSecurity;
//# sourceMappingURL=index.js.map