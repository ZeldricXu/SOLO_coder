package com.chaoslab.modules.mtls.strategy.impl;

import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class TestingEnvironmentStrategy implements CertificateStrategy {

    @Override
    public String getName() {
        return "TESTING_ENV";
    }

    @Override
    public String getDescription() {
        return "测试环境策略 - 使用RSA 1024位密钥加速生成，30天短有效期，自动清理过期证书";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public void beforeIssue(CertificateContext context) {
        context.setValidityDays(Math.min(context.getValidityDays(), 30));
        context.addAttribute("keySize", 1024);
        context.addAttribute("autoCleanup", true);
        context.addAttribute("skipValidation", true);
        log.info("TestingEnvironmentStrategy: Using 1024-bit keys for testing, 30-day max validity");
    }

    @Override
    public void beforeRevoke(RevocationRequest request, com.chaoslab.entity.MtlsCertificate cert) {
        log.info("TestingEnvironmentStrategy: Auto-revoking certificate for testing - {}", cert.getCertId());
        request.setReason(request.getReason() != null ? request.getReason() : "Testing environment cleanup");
    }

    @Override
    public void afterIssue(CertificateContext context, com.chaoslab.entity.MtlsCertificate cert) {
        cert.setNotAfter(LocalDateTime.now().plusDays(Math.min(context.getValidityDays(), 30)));
        log.info("TestingEnvironmentStrategy: Test certificate issued - will auto-expire in 30 days");
    }

    @Override
    public boolean shouldRotate(com.chaoslab.entity.MtlsCertificate cert) {
        LocalDateTime threshold = LocalDateTime.now().plusDays(7);
        return cert.getNotAfter().isBefore(threshold) && "active".equals(cert.getStatus());
    }
}
