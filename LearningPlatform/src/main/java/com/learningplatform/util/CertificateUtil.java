
package com.learningplatform.util;

import com.learningplatform.config.CertificateConfig;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class CertificateUtil {

    @Value("${learning.certificate.secret:LEARNING_PLATFORM_SECRET_KEY_2024}")
    private String secretKey;

    @Autowired
    private CertificateConfig certificateConfig;

    public static final String CERT_TYPE_COMPLETION = "completion";
    public static final String CERT_TYPE_ACHIEVEMENT = "achievement";
    public static final String CERT_TYPE_EXCELLENCE = "excellence";
    public static final String CERT_TYPE_PROFESSIONAL = "professional";

    public String getAlgorithmByCertificateType(String certificateType) {
        if (certificateConfig != null) {
            return certificateConfig.getAlgorithmByType(certificateType);
        }
        return "HMAC-SHA256";
    }

    public boolean isStrongSignatureType(String certificateType) {
        if (certificateConfig != null) {
            return certificateConfig.isStrongSignatureType(certificateType);
        }
        return "excellence".equals(certificateType) || "professional".equals(certificateType);
    }

    public String generateDigitalSignature(String certificateNumber, String studentId, 
                                           String courseId, LocalDateTime issuedAt) {
        return generateDigitalSignature(certificateNumber, studentId, courseId, issuedAt, CERT_TYPE_COMPLETION);
    }

    public String generateDigitalSignature(String certificateNumber, String studentId, 
                                           String courseId, LocalDateTime issuedAt, 
                                           String certificateType) {
        String data = String.join("|", 
                certificateNumber, 
                studentId, 
                courseId, 
                issuedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                certificateType);

        String algorithm = getAlgorithmByCertificateType(certificateType);
        return generateSignatureWithAlgorithm(data, algorithm);
    }

    private String generateSignatureWithAlgorithm(String data, String algorithm) {
        if ("HMAC-SHA512".equals(algorithm)) {
            return HmacUtils.hmacSha512Hex(secretKey.getBytes(StandardCharsets.UTF_8), data);
        } else {
            return HmacUtils.hmacSha256Hex(secretKey.getBytes(StandardCharsets.UTF_8), data);
        }
    }

    public boolean verifySignature(String certificateNumber, String studentId, 
                                   String courseId, LocalDateTime issuedAt, String signature) {
        return verifySignature(certificateNumber, studentId, courseId, issuedAt, signature, CERT_TYPE_COMPLETION);
    }

    public boolean verifySignature(String certificateNumber, String studentId, 
                                   String courseId, LocalDateTime issuedAt, 
                                   String signature, String certificateType) {
        String expectedSignature = generateDigitalSignature(
                certificateNumber, studentId, courseId, issuedAt, certificateType);
        return constantTimeEquals(expectedSignature, signature);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        
        byte[] bytesA = a.getBytes(StandardCharsets.UTF_8);
        byte[] bytesB = b.getBytes(StandardCharsets.UTF_8);
        
        int result = 0;
        for (int i = 0; i < bytesA.length; i++) {
            result |= bytesA[i] ^ bytesB[i];
        }
        return result == 0;
    }

    public LocalDateTime calculateValidUntil(LocalDateTime issuedAt, int validityYears) {
        return issuedAt.plusYears(validityYears);
    }

    public int getValidityYearsByType(String certificateType) {
        if (certificateConfig != null) {
            return certificateConfig.getValidityYearsByType(certificateType);
        }
        return getDefaultValidityYears(certificateType);
    }

    private int getDefaultValidityYears(String certificateType) {
        switch (certificateType) {
            case CERT_TYPE_EXCELLENCE:
                return 5;
            case CERT_TYPE_PROFESSIONAL:
                return 10;
            case CERT_TYPE_ACHIEVEMENT:
                return 3;
            case CERT_TYPE_COMPLETION:
            default:
                return 3;
        }
    }

    public String generateCertificateHash(String certificateNumber, String studentId, 
                                           String courseId, String certificateType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = certificateNumber + "|" + studentId + "|" + courseId + "|" + certificateType;
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public Map<String, String> getAvailableAlgorithms() {
        if (certificateConfig != null) {
            return certificateConfig.getAvailableAlgorithms();
        }
        return java.util.Collections.emptyMap();
    }

    public void updateAlgorithm(String certificateType, String algorithm) {
        if (certificateConfig != null) {
            certificateConfig.updateAlgorithm(certificateType, algorithm);
        }
    }

    public void updateValidityYears(String certificateType, int years) {
        if (certificateConfig != null) {
            certificateConfig.updateValidityYears(certificateType, years);
        }
    }

    public void removeCertificateType(String certificateType) {
        if (certificateConfig != null) {
            certificateConfig.removeCertificateType(certificateType);
        }
    }
}
