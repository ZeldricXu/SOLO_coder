package com.meshcontrol.mtls.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.mtls.dto.*;
import com.meshcontrol.mtls.entity.CaBundle;
import com.meshcontrol.mtls.entity.Certificate;
import com.meshcontrol.mtls.entity.CertificateRevocation;
import com.meshcontrol.mtls.mapper.CaBundleMapper;
import com.meshcontrol.mtls.mapper.CertificateMapper;
import com.meshcontrol.mtls.mapper.CertificateRevocationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateService extends BaseService<CertificateMapper, Certificate> {

    private final CertificateMapper certificateMapper;
    private final CaBundleMapper caBundleMapper;
    private final CertificateRevocationMapper revocationMapper;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Transactional
    public Certificate createRootCA(CertificateRequest request) {
        try {
            KeyPair keyPair = generateKeyPair(request.getKeyAlgorithm(), request.getKeySize());
            X509Certificate cert = generateSelfSignedCert(request, keyPair, true);

            Certificate certificate = new Certificate();
            certificate.setCertId(IdGenerator.generateId("cert"));
            certificate.setSerialNumber(cert.getSerialNumber().toString());
            certificate.setCommonName(request.getCommonName());
            certificate.setSans(request.getSans());
            certificate.setCertType("root_ca");
            certificate.setIssuer(request.getCommonName());
            certificate.setNotBefore(LocalDateTime.ofInstant(cert.getNotBefore().toInstant(), ZoneId.systemDefault()));
            certificate.setNotAfter(LocalDateTime.ofInstant(cert.getNotAfter().toInstant(), ZoneId.systemDefault()));
            certificate.setStatus("active");
            certificate.setPemData(convertToPem(cert));
            certificate.setPrivateKeyPem(convertPrivateKeyToPem(keyPair.getPrivate()));

            certificateMapper.insert(certificate);
            log.info("Root CA created: {}", certificate.getCertId());
            return certificate;
        } catch (Exception e) {
            log.error("Failed to create root CA", e);
            throw new BusinessException("Failed to create root CA: " + e.getMessage());
        }
    }

    @Transactional
    public Certificate issueCertificate(CertificateRequest request) {
        try {
            Certificate caCert;
            PrivateKey caKey;

            if (request.getSigningCaId() != null) {
                caCert = certificateMapper.selectById(request.getSigningCaId());
                if (caCert == null) {
                    throw new BusinessException("Signing CA not found");
                }
                caKey = parsePrivateKey(caCert.getPrivateKeyPem());
            } else {
                throw new BusinessException("signingCaId is required for issuing certificates");
            }

            KeyPair keyPair = generateKeyPair(request.getKeyAlgorithm(), request.getKeySize());
            X509Certificate cert = generateSignedCert(request, keyPair, caCert, caKey);

            Certificate certificate = new Certificate();
            certificate.setCertId(IdGenerator.generateId("cert"));
            certificate.setSerialNumber(cert.getSerialNumber().toString());
            certificate.setCommonName(request.getCommonName());
            certificate.setSans(request.getSans());
            certificate.setCertType(request.getCertType());
            certificate.setIssuer(caCert.getCommonName());
            certificate.setNotBefore(LocalDateTime.ofInstant(cert.getNotBefore().toInstant(), ZoneId.systemDefault()));
            certificate.setNotAfter(LocalDateTime.ofInstant(cert.getNotAfter().toInstant(), ZoneId.systemDefault()));
            certificate.setStatus("active");
            certificate.setPemData(convertToPem(cert));
            certificate.setPrivateKeyPem(convertPrivateKeyToPem(keyPair.getPrivate()));
            certificate.setIssuerCertId(caCert.getCertId());

            certificateMapper.insert(certificate);
            log.info("Certificate issued: {} type: {}", certificate.getCertId(), certificate.getCertType());
            return certificate;
        } catch (Exception e) {
            log.error("Failed to issue certificate", e);
            throw new BusinessException("Failed to issue certificate: " + e.getMessage());
        }
    }

    public Certificate getCertificate(String certId) {
        return certificateMapper.selectById(certId);
    }

    public IPage<Certificate> listCertificates(String certType, String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<Certificate> wrapper = new LambdaQueryWrapper<>();
        if (certType != null) {
            wrapper.eq(Certificate::getCertType, certType);
        }
        if (status != null) {
            wrapper.eq(Certificate::getStatus, status);
        }
        wrapper.orderByDesc(Certificate::getCreatedAt);
        return page(pageNum, pageSize, wrapper);
    }

    @Transactional
    public boolean revokeCertificate(RevocationRequest request) {
        Certificate cert = certificateMapper.selectById(request.getCertId());
        if (cert == null) {
            throw new BusinessException("Certificate not found");
        }

        cert.setStatus("revoked");
        certificateMapper.updateById(cert);

        CertificateRevocation revocation = new CertificateRevocation();
        revocation.setRevocationId(IdGenerator.generateId("rev"));
        revocation.setCertId(request.getCertId());
        revocation.setSerialNumber(cert.getSerialNumber());
        revocation.setReason(request.getReason());
        revocation.setRevokedAt(LocalDateTime.now());
        revocation.setCrlEntry(generateCrLEntry(cert, request.getReason()));
        revocationMapper.insert(revocation);

        log.info("Certificate revoked: {}", request.getCertId());
        return true;
    }

    public List<CertificateRevocation> getCRL() {
        return revocationMapper.findRecent(LocalDateTime.now().minusYears(1));
    }

    public Certificate getCertificateByCommonName(String commonName) {
        return certificateMapper.findActiveByCommonName(commonName);
    }

    @Transactional
    public Certificate rotateCertificate(String certId) {
        Certificate oldCert = certificateMapper.selectById(certId);
        if (oldCert == null) {
            throw new BusinessException("Certificate not found");
        }

        CertificateRequest request = new CertificateRequest();
        request.setCommonName(oldCert.getCommonName());
        request.setSans(oldCert.getSans());
        request.setCertType(oldCert.getCertType());
        request.setValidityDays(365);
        request.setSigningCaId(oldCert.getIssuerCertId());

        Certificate newCert = issueCertificate(request);
        oldCert.setStatus("rotated");
        certificateMapper.updateById(oldCert);

        log.info("Certificate rotated: {} -> {}", certId, newCert.getCertId());
        return newCert;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void checkCertificateExpiry() {
        LocalDateTime threshold = LocalDateTime.now().plusDays(30);
        List<Certificate> expiring = certificateMapper.findExpiringSoon(threshold);
        for (Certificate cert : expiring) {
            log.warn("Certificate expiring soon: {} (expires: {})", cert.getCertId(), cert.getNotAfter());
        }
    }

    @Transactional
    public CaBundle createCaBundle(CaBundleRequest request) {
        CaBundle bundle = new CaBundle();
        bundle.setBundleId(IdGenerator.generateId("cab"));
        bundle.setName(request.getName());
        bundle.setRootCertId(request.getRootCertId());
        bundle.setIntermediateCertIds(request.getIntermediateCertIds());
        bundle.setRotationDays(request.getRotationDays());
        bundle.setEnabled(request.getEnabled());

        caBundleMapper.insert(bundle);
        log.info("CA bundle created: {}", bundle.getBundleId());
        return bundle;
    }

    public List<CaBundle> listCaBundles() {
        return caBundleMapper.selectList(null);
    }

    @Transactional
    public boolean updateRotationPolicy(RotationPolicyRequest request) {
        CaBundle bundle = caBundleMapper.selectById(request.getBundleId());
        if (bundle == null) {
            throw new BusinessException("CA bundle not found");
        }
        if (request.getRotationDays() != null) {
            bundle.setRotationDays(request.getRotationDays());
        }
        caBundleMapper.updateById(bundle);
        return true;
    }

    private KeyPair generateKeyPair(String algorithm, int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(keySize, new SecureRandom());
        return generator.generateKeyPair();
    }

    private X509Certificate generateSelfSignedCert(CertificateRequest request, KeyPair keyPair, boolean isCA) throws Exception {
        X500Name subject = new X500Name("CN=" + request.getCommonName());
        BigInteger serial = new BigInteger(64, new SecureRandom());
        Date notBefore = new Date();
        Date notAfter = Date.from(LocalDateTime.now().plusDays(request.getValidityDays()).atZone(ZoneId.systemDefault()).toInstant());

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        if (isCA) {
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(
                    KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            certBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
            certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(
                    KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        }

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder);
    }

    private X509Certificate generateSignedCert(CertificateRequest request, KeyPair keyPair, Certificate caCert, PrivateKey caKey) throws Exception {
        X509Certificate caCertificate = parseCertificate(caCert.getPemData());
        X500Name issuer = new JcaX509CertificateHolder(caCertificate).getSubject();
        X500Name subject = new X500Name("CN=" + request.getCommonName());
        BigInteger serial = new BigInteger(64, new SecureRandom());
        Date notBefore = new Date();
        Date notAfter = Date.from(LocalDateTime.now().plusDays(request.getValidityDays()).atZone(ZoneId.systemDefault()).toInstant());

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, keyPair.getPublic());

        certBuilder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(
                KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(caKey);

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder);
    }

    private String convertToPem(X509Certificate cert) throws Exception {
        StringWriter writer = new StringWriter();
        writer.write("-----BEGIN CERTIFICATE-----\n");
        writer.write(Base64.getEncoder().encodeToString(cert.getEncoded()));
        writer.write("\n-----END CERTIFICATE-----\n");
        return writer.toString();
    }

    private String convertPrivateKeyToPem(PrivateKey key) throws Exception {
        StringWriter writer = new StringWriter();
        writer.write("-----BEGIN PRIVATE KEY-----\n");
        writer.write(Base64.getEncoder().encodeToString(key.getEncoded()));
        writer.write("\n-----END PRIVATE KEY-----\n");
        return writer.toString();
    }

    private X509Certificate parseCertificate(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        java.security.cert.CertificateFactory factory = java.security.cert.CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(encoded));
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(base64);
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(encoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private String generateCrLEntry(Certificate cert, String reason) {
        return "Serial: " + cert.getSerialNumber() + ", Reason: " + reason + ", Revoked: " + LocalDateTime.now();
    }

    public List<Certificate> getCertificateChain(String certId) {
        List<Certificate> chain = new ArrayList<>();
        Certificate current = certificateMapper.selectById(certId);
        while (current != null) {
            chain.add(current);
            if (current.getIssuerCertId() != null) {
                current = certificateMapper.selectById(current.getIssuerCertId());
            } else {
                break;
            }
        }
        return chain;
    }
}
