package com.chaoslab.modules.mtls.strategy.impl;

import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditLoggingStrategy implements CertificateStrategy {

    @Override
    public String getName() {
        return "AUDIT_LOGGING";
    }

    @Override
    public String getDescription() {
        return "审计日志策略 - 记录所有证书操作的详细审计信息";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public void beforeIssue(CertificateContext context) {
        logAuditEvent("CERTIFICATE_ISSUE_START", context.getRequest().getCommonName(), null);
    }

    @Override
    public void afterKeyGeneration(CertificateContext context) {
        String keyAlgorithm = context.getKeyPair().getPublic().getAlgorithm();
        int keySize = context.getKeyPair().getPublic().getEncoded().length * 8;
        logAuditEvent("KEY_GENERATED", context.getRequest().getCommonName(),
                String.format("algorithm=%s, size=%d", keyAlgorithm, keySize));
    }

    @Override
    public void afterPemConversion(CertificateContext context) {
        int certSize = context.getCertPem().length();
        int keySize = context.getPrivateKeyPem().length();
        logAuditEvent("PEM_GENERATED", context.getRequest().getCommonName(),
                String.format("certSize=%d, keySize=%d", certSize, keySize));
    }

    @Override
    public void afterIssue(CertificateContext context, com.chaoslab.entity.MtlsCertificate cert) {
        logAuditEvent("CERTIFICATE_ISSUED", cert.getCommonName(),
                String.format("certId=%s, serial=%s, notAfter=%s",
                        cert.getCertId(), cert.getSerialNumber(), cert.getNotAfter()));
    }

    @Override
    public void beforeRevoke(com.chaoslab.modules.mtls.dto.RevocationRequest request, com.chaoslab.entity.MtlsCertificate cert) {
        logAuditEvent("CERTIFICATE_REVOKE_START", cert.getCommonName(),
                String.format("certId=%s, reason=%s, revokedBy=%s",
                        cert.getCertId(), request.getReason(), request.getRevokedBy()));
    }

    @Override
    public void afterRevoke(com.chaoslab.modules.mtls.dto.RevocationRequest request, com.chaoslab.entity.MtlsRevocationList revocation) {
        logAuditEvent("CERTIFICATE_REVOKED", revocation.getCertId(),
                String.format("revocationId=%s, crlNumber=%d",
                        revocation.getRevocationId(), revocation.getCrlNumber()));
    }

    @Override
    public void onError(CertificateContext context, Exception e) {
        logAuditEvent("CERTIFICATE_ERROR",
                context.getRequest() != null ? context.getRequest().getCommonName() : "unknown",
                String.format("error=%s, message=%s", e.getClass().getSimpleName(), e.getMessage()));
    }

    private void logAuditEvent(String eventType, String entity, String details) {
        log.info("AUDIT [{}] entity={}, details={}", eventType, entity, details);
    }
}
