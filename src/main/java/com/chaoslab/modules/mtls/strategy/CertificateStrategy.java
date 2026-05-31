package com.chaoslab.modules.mtls.strategy;

import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;

public interface CertificateStrategy {

    String getName();

    String getDescription();

    default int getPriority() {
        return 100;
    }

    default boolean isEnabled() {
        return true;
    }

    default void beforeIssue(CertificateContext context) throws Exception {
    }

    default void afterKeyGeneration(CertificateContext context) throws Exception {
    }

    default void afterCertificateGeneration(CertificateContext context) throws Exception {
    }

    default void afterPemConversion(CertificateContext context) throws Exception {
    }

    default void beforePersist(CertificateContext context, MtlsCertificate cert) throws Exception {
    }

    default void afterIssue(CertificateContext context, MtlsCertificate cert) throws Exception {
    }

    default void beforeRevoke(RevocationRequest request, MtlsCertificate cert) throws Exception {
    }

    default void afterRevoke(RevocationRequest request, MtlsRevocationList revocation) throws Exception {
    }

    default boolean shouldRotate(MtlsCertificate cert) {
        return false;
    }

    default void onError(CertificateContext context, Exception e) {
    }
}
