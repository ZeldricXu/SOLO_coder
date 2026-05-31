package com.solocoder.dns.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.exception.BusinessException;
import com.solocoder.dns.common.exception.ResourceNotFoundException;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.mtls.model.Certificate;
import com.solocoder.dns.mtls.model.CertificateRevocation;
import com.solocoder.dns.mtls.model.RotationPolicy;
import com.solocoder.dns.persistence.entity.MtlsCertificatePO;
import com.solocoder.dns.persistence.entity.MtlsCrlPO;
import com.solocoder.dns.persistence.mapper.MtlsCertificateMapper;
import com.solocoder.dns.persistence.mapper.MtlsCrlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MtlsCertificateService {
    private final MtlsCertificateMapper certMapper;
    private final MtlsCrlMapper crlMapper;
    private final Map<String, RotationPolicy> policyStore = new ConcurrentHashMap<>();

    public Certificate issueCertificate(String commonName, Integer validityDays) {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            Certificate cert = new Certificate();
            cert.setCertId(IdGenerator.generateId("cert"));
            cert.setCommonName(commonName);
            cert.setSerialNumber(new BigInteger(64, new SecureRandom()).toString(16));
            cert.setCertificate("-----BEGIN CERTIFICATE-----\nMOCK_CERTIFICATE\n-----END CERTIFICATE-----");
            cert.setPrivateKey("-----BEGIN PRIVATE KEY-----\nMOCK_PRIVATE_KEY\n-----END PRIVATE KEY-----");
            cert.setIssuer("DNS-Platform-CA");
            cert.setNotBefore(LocalDateTime.now());
            cert.setNotAfter(LocalDateTime.now().plusDays(validityDays != null ? validityDays : 365));
            cert.setStatus("ACTIVE");
            cert.setCreatedAt(LocalDateTime.now());

            certMapper.insert(toCertPO(cert));
            log.info("Certificate issued: {} for {}", cert.getCertId(), commonName);
            return cert;
        } catch (Exception e) {
            throw new BusinessException("证书签发失败: " + e.getMessage());
        }
    }

    public Certificate getCertificate(String certId) {
        MtlsCertificatePO po = certMapper.selectById(certId);
        if (po == null) {
            throw new ResourceNotFoundException("Certificate", certId);
        }
        return toCertDomain(po);
    }

    public PageResult<Certificate> listCertificates(int page, int size, String status) {
        LambdaQueryWrapper<MtlsCertificatePO> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(MtlsCertificatePO::getStatus, status);
        }
        wrapper.orderByDesc(MtlsCertificatePO::getCreatedAt);
        Page<MtlsCertificatePO> poPage = certMapper.selectPage(new Page<>(page, size), wrapper);
        List<Certificate> items = poPage.getRecords().stream().map(this::toCertDomain).collect(Collectors.toList());
        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    public Certificate rotateCertificate(String certId) {
        Certificate existing = getCertificate(certId);
        Certificate newCert = issueCertificate(existing.getCommonName(), 365);
        existing.setStatus("ROTATED");
        existing.setRotatedAt(LocalDateTime.now());
        certMapper.updateById(toCertPO(existing));
        log.info("Certificate rotated: {} -> {}", certId, newCert.getCertId());
        return newCert;
    }

    public void revokeCertificate(String serialNumber, String reason) {
        CertificateRevocation revocation = new CertificateRevocation();
        revocation.setCrlId(IdGenerator.generateId("crl"));
        revocation.setSerialNumber(serialNumber);
        revocation.setReason(reason);
        revocation.setRevokedAt(LocalDateTime.now());
        revocation.setExpiresAt(LocalDateTime.now().plusYears(1));

        crlMapper.insert(toCrlPO(revocation));

        LambdaQueryWrapper<MtlsCertificatePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MtlsCertificatePO::getSerialNumber, serialNumber);
        MtlsCertificatePO cert = certMapper.selectOne(wrapper);
        if (cert != null) {
            cert.setStatus("REVOKED");
            certMapper.updateById(cert);
        }

        log.info("Certificate revoked: {}", serialNumber);
    }

    public boolean isRevoked(String serialNumber) {
        LambdaQueryWrapper<MtlsCrlPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MtlsCrlPO::getSerialNumber, serialNumber);
        wrapper.gt(MtlsCrlPO::getExpiresAt, LocalDateTime.now());
        return crlMapper.selectCount(wrapper) > 0;
    }

    public List<Certificate> getExpiringCertificates(int daysBeforeExpiry) {
        LocalDateTime threshold = LocalDateTime.now().plusDays(daysBeforeExpiry);
        LambdaQueryWrapper<MtlsCertificatePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MtlsCertificatePO::getStatus, "ACTIVE");
        wrapper.lt(MtlsCertificatePO::getNotAfter, threshold);
        return certMapper.selectList(wrapper).stream().map(this::toCertDomain).collect(Collectors.toList());
    }

    public RotationPolicy createRotationPolicy(RotationPolicy policy) {
        policy.setPolicyId(IdGenerator.generateId("rp"));
        policyStore.put(policy.getPolicyId(), policy);
        return policy;
    }

    public List<RotationPolicy> getAllRotationPolicies() {
        return List.copyOf(policyStore.values());
    }

    private MtlsCertificatePO toCertPO(Certificate cert) {
        MtlsCertificatePO po = new MtlsCertificatePO();
        po.setCertId(cert.getCertId());
        po.setCommonName(cert.getCommonName());
        po.setSerialNumber(cert.getSerialNumber());
        po.setCertificate(cert.getCertificate());
        po.setPrivateKey(cert.getPrivateKey());
        po.setIssuer(cert.getIssuer());
        po.setNotBefore(cert.getNotBefore());
        po.setNotAfter(cert.getNotAfter());
        po.setStatus(cert.getStatus());
        po.setCreatedAt(cert.getCreatedAt());
        po.setRotatedAt(cert.getRotatedAt());
        return po;
    }

    private Certificate toCertDomain(MtlsCertificatePO po) {
        Certificate cert = new Certificate();
        cert.setCertId(po.getCertId());
        cert.setCommonName(po.getCommonName());
        cert.setSerialNumber(po.getSerialNumber());
        cert.setCertificate(po.getCertificate());
        cert.setPrivateKey(po.getPrivateKey());
        cert.setIssuer(po.getIssuer());
        cert.setNotBefore(po.getNotBefore());
        cert.setNotAfter(po.getNotAfter());
        cert.setStatus(po.getStatus());
        cert.setCreatedAt(po.getCreatedAt());
        cert.setRotatedAt(po.getRotatedAt());
        return cert;
    }

    private MtlsCrlPO toCrlPO(CertificateRevocation revocation) {
        MtlsCrlPO po = new MtlsCrlPO();
        po.setCrlId(revocation.getCrlId());
        po.setSerialNumber(revocation.getSerialNumber());
        po.setReason(revocation.getReason());
        po.setRevokedAt(revocation.getRevokedAt());
        po.setExpiresAt(revocation.getExpiresAt());
        return po;
    }
}
