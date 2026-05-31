package com.chaoslab.modules.mtls.service;

import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.MtlsCertificate;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.MtlsCertificateMapper;
import com.chaoslab.mapper.MtlsRevocationListMapper;
import com.chaoslab.mapper.MtlsRotationPolicyMapper;
import com.chaoslab.modules.mtls.dto.*;
import com.chaoslab.modules.mtls.strategy.CertificateContext;
import com.chaoslab.modules.mtls.strategy.CertificateStrategy;
import com.chaoslab.modules.mtls.strategy.CertificateStrategyRegistry;
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
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MtlsCertificateStrategyService {

    private final MtlsCertificateMapper certificateMapper;
    private final MtlsRotationPolicyMapper rotationPolicyMapper;
    private final MtlsRevocationListMapper revocationListMapper;
    private final CertificateStrategyRegistry strategyRegistry;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // ==================== 策略管理 ====================

    public Mono<Map<String, Object>> listStrategies() {
        return Mono.fromCallable(strategyRegistry::getStrategyStats);
    }

    public Mono<String> activateStrategy(String strategyName, String operator, String reason) {
        return Mono.fromCallable(() -> {
            strategyRegistry.activateStrategy(strategyName);
            log.info("Strategy {} activated by {}: {}", strategyName, operator, reason);
            return "Strategy " + strategyName + " activated successfully";
        });
    }

    public Mono<String> deactivateStrategy(String strategyName, String operator, String reason) {
        return Mono.fromCallable(() -> {
            strategyRegistry.deactivateStrategy(strategyName);
            log.info("Strategy {} deactivated by {}: {}", strategyName, operator, reason);
            return "Strategy " + strategyName + " deactivated successfully";
        });
    }

    public Mono<String> switchStrategy(String fromStrategy, String toStrategy, String operator, String reason) {
        return Mono.fromCallable(() -> {
            if (fromStrategy != null && !"DEFAULT".equals(fromStrategy)) {
                strategyRegistry.deactivateStrategy(fromStrategy);
            }
            if (toStrategy != null) {
                strategyRegistry.activateStrategy(toStrategy);
            }
            log.info("Switched strategy from {} to {} by {}: {}", fromStrategy, toStrategy, operator, reason);
            return "Strategy switched from " + fromStrategy + " to " + toStrategy + " successfully";
        });
    }

    // ==================== 使用策略的证书操作 ====================

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<CertificateResponse> issueCertificateWithStrategy(CertificateIssueRequest request, String strategyName) {
        return Mono.fromCallable(() -> {
            CertificateContext context = new CertificateContext(request);
            context.setPolicyName(strategyName);
            List<CertificateStrategy> executionChain = strategyRegistry.getExecutionChain(strategyName);

            try {
                executeBeforeIssue(executionChain, context);

                int validityDays = determineValidityDays(request);
                context.setValidityDays(validityDays);

                executeAfterKeyGeneration(executionChain, context);

                KeyPair keyPair = generateKeyPair(
                        context.getAttribute("keySize") != null ?
                                (Integer) context.getAttribute("keySize") : 2048);
                context.setKeyPair(keyPair);

                executeAfterKeyGeneration(executionChain, context);

                X509Certificate certificate = generateSelfSignedCertificate(
                        request.getCommonName(),
                        keyPair,
                        context.getValidityDays(),
                        request.getOrganization(),
                        request.getOrganizationalUnit(),
                        request.getCountry()
                );
                context.setCertificate(certificate);
                context.setNotBefore(LocalDateTime.ofInstant(certificate.getNotBefore().toInstant(), ZoneId.systemDefault()));
                context.setNotAfter(LocalDateTime.ofInstant(certificate.getNotAfter().toInstant(), ZoneId.systemDefault()));

                executeAfterCertificateGeneration(executionChain, context);

                String certPem = convertToPem(certificate);
                String privateKeyPem = convertPrivateKeyToPem(keyPair.getPrivate());
                context.setCertPem(certPem);
                context.setPrivateKeyPem(privateKeyPem);

                executeAfterPemConversion(executionChain, context);

                MtlsCertificate cert = buildCertificateEntity(context, request);

                executeBeforePersist(executionChain, context, cert);

                certificateMapper.insert(cert);

                executeAfterIssue(executionChain, context, cert);

                log.info("Issued certificate with strategy chain: {} for CN={}",
                        executionChain.stream().map(CertificateStrategy::getName).toList(),
                        request.getCommonName());

                return toCertificateResponse(cert);

            } catch (Exception e) {
                executeOnError(executionChain, context, e);
                log.error("Failed to issue certificate with strategy: {}", strategyName, e);
                if (e instanceof BusinessException) {
                    throw e;
                }
                throw new BusinessException(500, "证书签发失败: " + e.getMessage());
            }
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<MtlsRevocationList> revokeCertificateWithStrategy(RevocationRequest request, String strategyName) {
        return Mono.fromCallable(() -> {
            List<CertificateStrategy> executionChain = strategyRegistry.getExecutionChain(strategyName);

            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MtlsCertificate> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            wrapper.eq(MtlsCertificate::getCertId, request.getCertId());
            MtlsCertificate cert = certificateMapper.selectOne(wrapper);
            if (cert == null) {
                throw BusinessException.notFound("证书不存在: " + request.getCertId());
            }
            if ("revoked".equals(cert.getStatus())) {
                throw BusinessException.validationError("证书已被吊销");
            }

            executeBeforeRevoke(executionChain, request, cert);

            cert.setStatus("revoked");
            certificateMapper.updateById(cert);

            MtlsRevocationList revocation = buildRevocationEntity(request, cert);
            revocationListMapper.insert(revocation);

            executeAfterRevoke(executionChain, request, revocation);

            log.info("Revoked certificate with strategy chain: {} - certId={}",
                    executionChain.stream().map(CertificateStrategy::getName).toList(),
                    request.getCertId());

            return revocation;
        });
    }

    public Flux<CertificateResponse> rotateExpiringCertificatesWithStrategy(String strategyName) {
        return Flux.defer(() -> {
            List<CertificateStrategy> executionChain = strategyRegistry.getExecutionChain(strategyName);
            LocalDateTime rotationThreshold = LocalDateTime.now().plusDays(30);

            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MtlsCertificate> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            wrapper.eq(MtlsCertificate::getStatus, "active")
                    .lt(MtlsCertificate::getNotAfter, rotationThreshold)
                    .isNotNull(MtlsCertificate::getRotationPolicyId);

            List<MtlsCertificate> expiringCerts = certificateMapper.selectList(wrapper);

            List<MtlsCertificate> toRotate = expiringCerts.stream()
                    .filter(cert -> executionChain.stream().anyMatch(s -> s.shouldRotate(cert)))
                    .toList();

            log.info("Found {} certificates needing rotation with strategy {}",
                    toRotate.size(), strategyName);

            return Flux.fromIterable(toRotate)
                    .flatMap(cert -> {
                        CertificateIssueRequest request = new CertificateIssueRequest();
                        request.setCommonName(cert.getCommonName());
                        request.setRotationPolicyId(cert.getRotationPolicyId());
                        return issueCertificateWithStrategy(request, strategyName)
                                .doOnSuccess(newCert -> {
                                    cert.setStatus("rotated");
                                    certificateMapper.updateById(cert);
                                    log.info("Rotated certificate: {} -> {}", cert.getCertId(), newCert.getCertId());
                                });
                    });
        });
    }

    // ==================== 策略执行方法 ====================

    private void executeBeforeIssue(List<CertificateStrategy> chain, CertificateContext context) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.beforeIssue(context);
            } catch (Exception e) {
                log.error("Strategy {} failed in beforeIssue", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeAfterKeyGeneration(List<CertificateStrategy> chain, CertificateContext context) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.afterKeyGeneration(context);
            } catch (Exception e) {
                log.error("Strategy {} failed in afterKeyGeneration", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeAfterCertificateGeneration(List<CertificateStrategy> chain, CertificateContext context) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.afterCertificateGeneration(context);
            } catch (Exception e) {
                log.error("Strategy {} failed in afterCertificateGeneration", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeAfterPemConversion(List<CertificateStrategy> chain, CertificateContext context) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.afterPemConversion(context);
            } catch (Exception e) {
                log.error("Strategy {} failed in afterPemConversion", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeBeforePersist(List<CertificateStrategy> chain, CertificateContext context, MtlsCertificate cert) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.beforePersist(context, cert);
            } catch (Exception e) {
                log.error("Strategy {} failed in beforePersist", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeAfterIssue(List<CertificateStrategy> chain, CertificateContext context, MtlsCertificate cert) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.afterIssue(context, cert);
            } catch (Exception e) {
                log.error("Strategy {} failed in afterIssue", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeBeforeRevoke(List<CertificateStrategy> chain, RevocationRequest request, MtlsCertificate cert) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.beforeRevoke(request, cert);
            } catch (Exception e) {
                log.error("Strategy {} failed in beforeRevoke", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeAfterRevoke(List<CertificateStrategy> chain, RevocationRequest request, MtlsRevocationList revocation) throws Exception {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.afterRevoke(request, revocation);
            } catch (Exception e) {
                log.error("Strategy {} failed in afterRevoke", strategy.getName(), e);
                throw e;
            }
        }
    }

    private void executeOnError(List<CertificateStrategy> chain, CertificateContext context, Exception e) {
        for (CertificateStrategy strategy : chain) {
            try {
                strategy.onError(context, e);
            } catch (Exception ex) {
                log.error("Strategy {} failed in onError", strategy.getName(), ex);
            }
        }
    }

    // ==================== 原有核心方法 ====================

    private int determineValidityDays(CertificateIssueRequest request) {
        int validityDays = request.getValidityDays() != null ? request.getValidityDays() : 365;
        if (request.getRotationPolicyId() != null) {
            MtlsRotationPolicy policy = rotationPolicyMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MtlsRotationPolicy>()
                            .eq(MtlsRotationPolicy::getPolicyId, request.getRotationPolicyId())
                            .eq(MtlsRotationPolicy::getEnabled, true));
            if (policy != null) {
                validityDays = policy.getValidityDays();
            }
        }
        return validityDays;
    }

    private KeyPair generateKeyPair(int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
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

    private MtlsCertificate buildCertificateEntity(CertificateContext context, CertificateIssueRequest request) {
        MtlsCertificate cert = new MtlsCertificate();
        cert.setCertId("cert-" + UUID.randomUUID().toString().substring(0, 8));
        cert.setCommonName(request.getCommonName());
        cert.setSerialNumber(context.getCertificate().getSerialNumber().toString(16));
        cert.setCertificatePem(context.getCertPem());
        cert.setPrivateKeyPem(context.getPrivateKeyPem());
        cert.setIssuer(context.getCertificate().getIssuerX500Principal().getName());
        cert.setNotBefore(context.getNotBefore());
        cert.setNotAfter(context.getNotAfter());
        cert.setStatus("active");
        cert.setRotationPolicyId(request.getRotationPolicyId());
        if (context.getPolicyName() != null) {
            cert.getAdditionalProperties().put("appliedStrategy", context.getPolicyName());
        }
        return cert;
    }

    private MtlsRevocationList buildRevocationEntity(RevocationRequest request, MtlsCertificate cert) {
        MtlsRevocationList revocation = new MtlsRevocationList();
        revocation.setRevocationId("rev-" + UUID.randomUUID().toString().substring(0, 8));
        revocation.setCertId(request.getCertId());
        revocation.setSerialNumber(cert.getSerialNumber());
        revocation.setReason(request.getReason());
        revocation.setRevokedAt(LocalDateTime.now());
        revocation.setRevokedBy(request.getRevokedBy());

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MtlsRevocationList> countWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        Long crlCount = revocationListMapper.selectCount(countWrapper);
        revocation.setCrlNumber(crlCount.intValue() + 1);

        return revocation;
    }

    private CertificateResponse toCertificateResponse(MtlsCertificate cert) {
        CertificateResponse response = new CertificateResponse();
        BeanUtils.copyProperties(cert, response);
        LocalDateTime rotationThreshold = LocalDateTime.now().plusDays(30);
        response.setNeedsRotation(cert.getNotAfter().isBefore(rotationThreshold)
                && "active".equals(cert.getStatus()));
        return response;
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
}
