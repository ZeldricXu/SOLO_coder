package com.chaoslab.modules.mtls.strategy.impl;

import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StrictSecurityStrategy implements CertificateStrategy {

    @Override
    public String getName() {
        return "STRICT_SECURITY";
    }

    @Override
    public String getDescription() {
        return "严格安全策略 - 使用RSA 4096位密钥，90天短有效期，增强密钥用途限制";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public void beforeIssue(CertificateContext context) {
        context.setValidityDays(Math.min(context.getValidityDays(), 90));
        context.addAttribute("keySize", 4096);
        context.addAttribute("enforceKeyUsage", true);
        log.info("StrictSecurityStrategy: Enforcing 90-day max validity and 4096-bit keys");
    }

    @Override
    public void afterKeyGeneration(CertificateContext context) {
        int keySize = context.getKeyPair().getPublic().getEncoded().length * 8;
        if (keySize < 4096) {
            throw new SecurityException("Strict security requires minimum 4096-bit key, got " + keySize);
        }
        log.debug("StrictSecurityStrategy: Key size verified - {} bits", keySize);
    }

    @Override
    public void afterCertificateGeneration(CertificateContext context) throws Exception {
        context.addAttribute("enhancedKeyUsage",
                new ExtendedKeyUsage(new KeyPurposeId[]{
                        KeyPurposeId.id_kp_serverAuth,
                        KeyPurposeId.id_kp_clientAuth
                }));
        context.addAttribute("keyUsage",
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment));
        log.debug("StrictSecurityStrategy: Enhanced key usage restrictions applied");
    }

    @Override
    public boolean shouldRotate(com.chaoslab.entity.MtlsCertificate cert) {
        java.time.LocalDateTime threshold = java.time.LocalDateTime.now().plusDays(15);
        return cert.getNotAfter().isBefore(threshold) && "active".equals(cert.getStatus());
    }
}
