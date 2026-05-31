package com.chaoslab.modules.mtls.strategy;

import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import lombok.Data;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CertificateContext {
    private CertificateIssueRequest request;
    private KeyPair keyPair;
    private X509Certificate certificate;
    private String certPem;
    private String privateKeyPem;
    private int validityDays;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private Map<String, Object> attributes;
    private String policyName;

    public CertificateContext(CertificateIssueRequest request) {
        this.request = request;
    }

    public void addAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new java.util.HashMap<>();
        }
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }
}
