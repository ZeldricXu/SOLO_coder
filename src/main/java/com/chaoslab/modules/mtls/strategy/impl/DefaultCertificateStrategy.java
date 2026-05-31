package com.chaoslab.modules.mtls.strategy.impl;

import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DefaultCertificateStrategy implements CertificateStrategy {

    @Override
    public String getName() {
        return "DEFAULT";
    }

    @Override
    public String getDescription() {
        return "默认证书策略 - 使用标准RSA 2048位密钥，365天有效期";
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void beforeIssue(CertificateContext context) {
        if (context.getValidityDays() <= 0) {
            context.setValidityDays(365);
        }
        log.debug("DefaultStrategy: beforeIssue - setting validity to {} days", context.getValidityDays());
    }

    @Override
    public void afterIssue(CertificateContext context, com.chaoslab.entity.MtlsCertificate cert) {
        log.info("DefaultStrategy: Certificate issued successfully - CN={}, certId={}",
                context.getRequest().getCommonName(), cert.getCertId());
    }

    @Override
    public boolean shouldRotate(com.chaoslab.entity.MtlsCertificate cert) {
        java.time.LocalDateTime threshold = java.time.LocalDateTime.now().plusDays(30);
        return cert.getNotAfter().isBefore(threshold) && "active".equals(cert.getStatus());
    }
}
