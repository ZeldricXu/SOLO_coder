package com.chaoslab.modules.mtls.strategy.impl;

import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExtendedValidationStrategy implements CertificateStrategy {

    @Override
    public String getName() {
        return "EXTENDED_VALIDATION";
    }

    @Override
    public String getDescription() {
        return "扩展验证策略 - 强制执行完整的证书主题字段验证，添加扩展属性";
    }

    @Override
    public int getPriority() {
        return 15;
    }

    @Override
    public void beforeIssue(CertificateContext context) {
        validateSubjectFields(context);
        addExtendedAttributes(context);
        log.info("ExtendedValidationStrategy: Subject fields validated for EV certificate");
    }

    @Override
    public void afterCertificateGeneration(CertificateContext context) throws Exception {
        X500Name subject = new org.bouncycastle.asn1.x500.X500Name(
                context.getCertificate().getSubjectX500Principal().getName());
        context.addAttribute("subjectDn", subject.toString());
        context.addAttribute("validationLevel", "EXTENDED");
        context.addAttribute("evPolicy", "2.23.140.1.1");
        log.debug("ExtendedValidationStrategy: Extended validation attributes added");
    }

    private void validateSubjectFields(CertificateContext context) {
        var request = context.getRequest();
        if (request.getOrganization() == null || request.getOrganization().isEmpty()) {
            throw new IllegalArgumentException("Extended validation requires organization");
        }
        if (request.getCountry() == null || request.getCountry().isEmpty()) {
            throw new IllegalArgumentException("Extended validation requires country code");
        }
        if (request.getCommonName() == null || !request.getCommonName().contains(".")) {
            throw new IllegalArgumentException("Extended validation requires valid FQDN as common name");
        }
    }

    private void addExtendedAttributes(CertificateContext context) {
        context.addAttribute("businessCategory", "Private Organization");
        context.addAttribute("jurisdictionCountry", context.getRequest().getCountry());
        context.addAttribute("serialNumber", "EV-" + System.currentTimeMillis());
    }
}
