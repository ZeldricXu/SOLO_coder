package com.chaoslab.modules.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.MtlsCertificateMapper;
import com.chaoslab.mapper.MtlsRevocationListMapper;
import com.chaoslab.mapper.MtlsRotationPolicyMapper;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.CertificateResponse;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.dto.RotationPolicyCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MtlsCertificateService {

    private final MtlsCertificateMapper certificateMapper;
    private final MtlsRotationPolicyMapper rotationPolicyMapper;
    private final MtlsRevocationListMapper revocationListMapper;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Transactional
    public Mono<MtlsRotationPolicy> createRotationPolicy(RotationPolicyCreateRequest request) {
        return Mono.fromCallable(() -> {
            MtlsRotationPolicy policy = new MtlsRotationPolicy();
            policy.setPolicyId("rp-" + UUID.randomUUID().toString().substring(0, 8));
            policy.setName(request.getName());
            policy.setValidityDays(request.getValidityDays());
            policy.setRotationDays(request.getRotationDays());
            policy.setAutoRotate(request.getAutoRotate());
            policy.setKeyAlgorithm(request.getKeyAlgorithm());
            policy.setKeySize(request.getKeySize());
            policy.setSignatureAlgorithm(request.getSignatureAlgorithm());
            policy.setEnabled(request.getEnabled());

            rotationPolicyMapper.insert(policy);
            log.info("Created rotation policy: {}", policy.getPolicyId());
            return policy;
        });
    }

    public Mono<List<MtlsRotationPolicy>> listRotationPolicies() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MtlsRotationPolicy> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(MtlsRotationPolicy::getCreatedAt);
            return rotationPolicyMapper.selectList(wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CertificateResponse> issueCertificate(CertificateIssueRequest request) {
        return Mono.fromCallable(() -> {
            int validityDays = request.getValidityDays() != null ? request.getValidityDays() : 365;
            if (request.getRotationPolicyId() != null) {
                MtlsRotationPolicy policy = rotationPolicyMapper.selectOne(
                        new LambdaQueryWrapper<MtlsRotationPolicy>()
                                .eq(MtlsRotationPolicy::getPolicyId, request.getRotationPolicyId())
                                .eq(MtlsRotationPolicy::getEnabled, true));
                if (policy != null) {
                    validityDays = policy.getValidityDays();
                }
            }

            KeyPair keyPair = generateKeyPair("RSA", 2048);
            X509Certificate certificate = generateSelfSignedCertificate(
                    request.getCommonName(),
                    keyPair,
                    validityDays,
                    request.getOrganization(),
                    request.getOrganizationalUnit(),
                    request.getCountry()
            );

            String certPem = convertToPem(certificate);
            String privateKeyPem = convertPrivateKeyToPem(keyPair.getPrivate());

            MtlsCertificate cert = new MtlsCertificate();
            cert.setCertId("cert-" + UUID.randomUUID().toString().substring(0, 8));
            cert.setCommonName(request.getCommonName());
            cert.setSerialNumber(certificate.getSerialNumber().toString(16));
            cert.setCertificatePem(certPem);
            cert.setPrivateKeyPem(privateKeyPem);
            cert.setIssuer(certificate.getIssuerX500Principal().getName());
            cert.setNotBefore(LocalDateTime.ofInstant(certificate.getNotBefore().toInstant(), ZoneId.systemDefault()));
            cert.setNotAfter(LocalDateTime.ofInstant(certificate.getNotAfter().toInstant(), ZoneId.systemDefault()));
            cert.setStatus("active");
            cert.setRotationPolicyId(request.getRotationPolicyId());

            certificateMapper.insert(cert);
            log.info("Issued certificate: {} for CN={}", cert.getCertId(), request.getCommonName());

            return toCertificateResponse(cert);
        });
    }

    public Mono<CertificateResponse> getCertificate(String certId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MtlsCertificate> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MtlsCertificate::getCertId, certId);
            MtlsCertificate cert = certificateMapper.selectOne(wrapper);
            if (cert == null) {
                throw BusinessException.notFound("证书不存在: " + certId);
            }
            return toCertificateResponse(cert);
        });
    }

    public Mono<List<CertificateResponse>> listCertificates(String status, String commonName) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MtlsCertificate> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(MtlsCertificate::getStatus, status);
            }
            if (commonName != null && !commonName.isEmpty()) {
                wrapper.like(MtlsCertificate::getCommonName, commonName);
            }
            wrapper.orderByDesc(MtlsCertificate::getCreatedAt);
            return certificateMapper.selectList(wrapper).stream()
                    .map(this::toCertificateResponse)
                    .toList();
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MtlsRevocationList> revokeCertificate(RevocationRequest request) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MtlsCertificate> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MtlsCertificate::getCertId, request.getCertId());
            MtlsCertificate cert = certificateMapper.selectOne(wrapper);
            if (cert == null) {
                throw BusinessException.notFound("证书不存在: " + request.getCertId());
            }
            if ("revoked".equals(cert.getStatus())) {
                throw BusinessException.validationError("证书已被吊销");
            }

            cert.setStatus("revoked");
            certificateMapper.updateById(cert);

            MtlsRevocationList revocation = new MtlsRevocationList();
            revocation.setRevocationId("rev-" + UUID.randomUUID().toString().substring(0, 8));
            revocation.setCertId(request.getCertId());
            revocation.setSerialNumber(cert.getSerialNumber());
            revocation.setReason(request.getReason());
            revocation.setRevokedAt(LocalDateTime.now());
            revocation.setRevokedBy(request.getRevokedBy());

            LambdaQueryWrapper<MtlsRevocationList> countWrapper = new LambdaQueryWrapper<>();
            Long crlCount = revocationListMapper.selectCount(countWrapper);
            revocation.setCrlNumber(crlCount.intValue() + 1);

            revocationListMapper.insert(revocation);
            log.info("Revoked certificate: {} by {}", request.getCertId(), request.getRevokedBy());
            return revocation;
        });
    }

    public Mono<List<MtlsRevocationList>> getRevocationList() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<MtlsRevocationList> wrapper = new LambdaQueryWrapper<>();
            wrapper.orderByDesc(MtlsRevocationList::getRevokedAt);
            return revocationListMapper.selectList(wrapper);
        });
    }

    public Mono<String> getCrl() {
        return getRevocationList()
                .map(revocations -> {
                    StringBuilder crlBuilder = new StringBuilder();
                    crlBuilder.append("-----BEGIN X509 CRL-----\n");
                    crlBuilder.append("Issuer: CN=ChaosLab CA\n");
                    crlBuilder.append("Last Update: ").append(LocalDateTime.now()).append("\n");
                    crlBuilder.append("Next Update: ").append(LocalDateTime.now().plusDays(7)).append("\n");
                    crlBuilder.append("Revoked Certificates:\n");
                    for (MtlsRevocationList rev : revocations) {
                        crlBuilder.append("    Serial Number: ").append(rev.getSerialNumber()).append("\n");
                        crlBuilder.append("    Revocation Date: ").append(rev.getRevokedAt()).append("\n");
                        if (rev.getReason() != null) {
                            crlBuilder.append("    Reason: ").append(rev.getReason()).append("\n");
                        }
                        crlBuilder.append("\n");
                    }
                    crlBuilder.append("-----END X509 CRL-----\n");
                    return crlBuilder.toString();
                });
    }

    @Transactional
    public Flux<CertificateResponse> rotateExpiringCertificates() {
        return Flux.defer(() -> {
            LocalDateTime rotationThreshold = LocalDateTime.now().plusDays(30);
            LambdaQueryWrapper<MtlsCertificate> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MtlsCertificate::getStatus, "active")
                    .lt(MtlsCertificate::getNotAfter, rotationThreshold)
                    .isNotNull(MtlsCertificate::getRotationPolicyId);

            List<MtlsCertificate> expiringCerts = certificateMapper.selectList(wrapper);
            log.info("Found {} certificates needing rotation", expiringCerts.size());

            return Flux.fromIterable(expiringCerts)
                    .flatMap(this::rotateSingleCertificate);
        });
    }

    private Mono<CertificateResponse> rotateSingleCertificate(MtlsCertificate oldCert) {
        return Mono.fromCallable(() -> {
            CertificateIssueRequest request = new CertificateIssueRequest();
            request.setCommonName(oldCert.getCommonName());
            request.setRotationPolicyId(oldCert.getRotationPolicyId());
            return request;
        }).flatMap(this::issueCertificate)
                .doOnSuccess(newCert -> {
                    oldCert.setStatus("rotated");
                    certificateMapper.updateById(oldCert);
                    log.info("Rotated certificate: {} -> {}", oldCert.getCertId(), newCert.getCertId());
                });
    }

    public boolean isCertificateRevoked(String serialNumber) {
        LambdaQueryWrapper<MtlsRevocationList> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MtlsRevocationList::getSerialNumber, serialNumber);
        return revocationListMapper.selectCount(wrapper) > 0;
    }

    private KeyPair generateKeyPair(String algorithm, int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(algorithm, "BC");
        keyGen.initialize(keySize, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    private X509Certificate generateSelfSignedCertificate(String commonName, KeyPair keyPair,
                                                          int validityDays, String organization,
                                                          String organizationalUnit, String country)
            throws Exception {
        X500Name issuer = buildX500Name(commonName, organization, organizationalUnit, country);
        X500Name subject = issuer;

        BigInteger serialNumber = new BigInteger(128, new SecureRandom());
        Date notBefore = new Date();
        Date notAfter = Date.from(LocalDateTime.now().plusDays(validityDays)
                .atZone(ZoneId.systemDefault()).toInstant());

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer,
                serialNumber,
                notBefore,
                notAfter,
                subject,
                keyPair.getPublic()
        );

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder);
    }

    private X500Name buildX500Name(String commonName, String organization,
                                   String organizationalUnit, String country) {
        StringBuilder dn = new StringBuilder();
        if (country != null) dn.append("C=").append(country).append(", ");
        if (organization != null) dn.append("O=").append(organization).append(", ");
        if (organizationalUnit != null) dn.append("OU=").append(organizationalUnit).append(", ");
        dn.append("CN=").append(commonName);
        return new X500Name(dn.toString());
    }

    private String convertToPem(X509Certificate certificate) throws Exception {
        StringWriter writer = new StringWriter();
        PemWriter pemWriter = new PemWriter(writer);
        pemWriter.writeObject(new PemObject("CERTIFICATE", certificate.getEncoded()));
        pemWriter.close();
        return writer.toString();
    }

    private String convertPrivateKeyToPem(PrivateKey privateKey) throws Exception {
        StringWriter writer = new StringWriter();
        PemWriter pemWriter = new PemWriter(writer);
        pemWriter.writeObject(new PemObject("PRIVATE KEY", privateKey.getEncoded()));
        pemWriter.close();
        return writer.toString();
    }

    private CertificateResponse toCertificateResponse(MtlsCertificate cert) {
        CertificateResponse response = new CertificateResponse();
        BeanUtils.copyProperties(cert, response);
        LocalDateTime rotationThreshold = LocalDateTime.now().plusDays(30);
        response.setNeedsRotation(cert.getNotAfter().isBefore(rotationThreshold)
                && "active".equals(cert.getStatus()));
        return response;
    }
}
