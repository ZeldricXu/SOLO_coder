
package com.learningplatform.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@DisplayName("CertificateUtil 证书签名工具测试")
class CertificateUtilTest {

    private CertificateUtil certificateUtil;
    
    private static final String TEST_SECRET = "TEST_SECRET_KEY_FOR_UNIT_TESTING";
    private static final String TEST_CERT_NUMBER = "CERT20260511001";
    private static final String TEST_STUDENT_ID = "student_test_001";
    private static final String TEST_COURSE_ID = "course_test_001";
    private static final LocalDateTime TEST_ISSUED_AT = LocalDateTime.of(2026, 5, 11, 10, 0, 0);

    @BeforeEach
    void setUp() {
        certificateUtil = new CertificateUtil();
        ReflectionTestUtils.setField(certificateUtil, "secretKey", TEST_SECRET);
    }

    @Nested
    @DisplayName("签名算法测试")
    class SignatureAlgorithmTests {

        @Test
        @DisplayName("普通证书应该使用HMAC-SHA256算法")
        void shouldUseSHA256ForCompletionCertificate() {
            String algorithm = certificateUtil.getAlgorithmByCertificateType(CertificateUtil.CERT_TYPE_COMPLETION);
            assertEquals("HMAC-SHA256", algorithm);
        }

        @Test
        @DisplayName("成就证书应该使用HMAC-SHA256算法")
        void shouldUseSHA256ForAchievementCertificate() {
            String algorithm = certificateUtil.getAlgorithmByCertificateType(CertificateUtil.CERT_TYPE_ACHIEVEMENT);
            assertEquals("HMAC-SHA256", algorithm);
        }

        @Test
        @DisplayName("优秀证书应该使用HMAC-SHA512算法")
        void shouldUseSHA512ForExcellenceCertificate() {
            String algorithm = certificateUtil.getAlgorithmByCertificateType(CertificateUtil.CERT_TYPE_EXCELLENCE);
            assertEquals("HMAC-SHA512", algorithm);
        }

        @Test
        @DisplayName("专业证书应该使用HMAC-SHA512算法")
        void shouldUseSHA512ForProfessionalCertificate() {
            String algorithm = certificateUtil.getAlgorithmByCertificateType(CertificateUtil.CERT_TYPE_PROFESSIONAL);
            assertEquals("HMAC-SHA512", algorithm);
        }

        @Test
        @DisplayName("应该正确识别强签名类型")
        void shouldIdentifyStrongSignatureType() {
            assertTrue(certificateUtil.isStrongSignatureType(CertificateUtil.CERT_TYPE_EXCELLENCE));
            assertTrue(certificateUtil.isStrongSignatureType(CertificateUtil.CERT_TYPE_PROFESSIONAL));
            assertFalse(certificateUtil.isStrongSignatureType(CertificateUtil.CERT_TYPE_COMPLETION));
            assertFalse(certificateUtil.isStrongSignatureType(CertificateUtil.CERT_TYPE_ACHIEVEMENT));
        }

        @Test
        @DisplayName("未知证书类型应该使用默认HMAC-SHA256算法")
        void shouldUseDefaultAlgorithmForUnknownType() {
            String algorithm = certificateUtil.getAlgorithmByCertificateType("unknown_type");
            assertEquals("HMAC-SHA256", algorithm);
        }
    }

    @Nested
    @DisplayName("签名生成测试")
    class SignatureGenerationTests {

        @Test
        @DisplayName("应该生成非空签名")
        void shouldGenerateNonNullSignature() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            
            assertNotNull(signature);
            assertFalse(signature.isEmpty());
        }

        @Test
        @DisplayName("相同输入应该生成相同签名")
        void shouldGenerateSameSignatureForSameInput() {
            String signature1 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            String signature2 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            
            assertEquals(signature1, signature2);
        }

        @Test
        @DisplayName("不同证书编号应该生成不同签名")
        void shouldGenerateDifferentSignatureForDifferentCertNumber() {
            String signature1 = certificateUtil.generateDigitalSignature(
                    "CERT001", TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            String signature2 = certificateUtil.generateDigitalSignature(
                    "CERT002", TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            
            assertNotEquals(signature1, signature2);
        }

        @Test
        @DisplayName("不同学员ID应该生成不同签名")
        void shouldGenerateDifferentSignatureForDifferentStudent() {
            String signature1 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, "student_001", TEST_COURSE_ID, TEST_ISSUED_AT);
            String signature2 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, "student_002", TEST_COURSE_ID, TEST_ISSUED_AT);
            
            assertNotEquals(signature1, signature2);
        }

        @Test
        @DisplayName("不同课程ID应该生成不同签名")
        void shouldGenerateDifferentSignatureForDifferentCourse() {
            String signature1 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, "course_001", TEST_ISSUED_AT);
            String signature2 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, "course_002", TEST_ISSUED_AT);
            
            assertNotEquals(signature1, signature2);
        }

        @Test
        @DisplayName("不同签发时间应该生成不同签名")
        void shouldGenerateDifferentSignatureForDifferentTime() {
            String signature1 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            String signature2 = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT.plusHours(1));
            
            assertNotEquals(signature1, signature2);
        }

        @Test
        @DisplayName("不同证书类型应该生成不同签名（强签名vs普通签名）")
        void shouldGenerateDifferentSignatureForDifferentTypes() {
            String signatureNormal = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_COMPLETION);
            String signatureStrong = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_EXCELLENCE);
            
            assertNotEquals(signatureNormal, signatureStrong);
        }

        @Test
        @DisplayName("强签名应该比普通签名更长")
        void strongSignatureShouldBeLonger() {
            String signatureNormal = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_COMPLETION);
            String signatureStrong = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_EXCELLENCE);
            
            assertNotNull(signatureNormal);
            assertNotNull(signatureStrong);
            assertTrue(signatureStrong.length() > signatureNormal.length());
        }
    }

    @Nested
    @DisplayName("签名验证测试")
    class SignatureVerificationTests {

        @Test
        @DisplayName("有效签名应该验证通过")
        void shouldPassVerificationForValidSignature() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);

            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, signature);

            assertTrue(valid);
        }

        @Test
        @DisplayName("无效签名应该验证失败")
        void shouldFailVerificationForInvalidSignature() {
            String invalidSignature = "invalid_signature_string_1234567890";

            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, invalidSignature);

            assertFalse(valid);
        }

        @Test
        @DisplayName("被篡改的签名应该验证失败")
        void shouldFailVerificationForTamperedSignature() {
            String originalSignature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);

            char[] chars = originalSignature.toCharArray();
            chars[0] = (chars[0] == 'a') ? 'b' : 'a';
            String tamperedSignature = new String(chars);

            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, tamperedSignature);

            assertFalse(valid);
        }

        @Test
        @DisplayName("使用错误数据验证应该失败")
        void shouldFailVerificationWithWrongData() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);

            boolean valid = certificateUtil.verifySignature(
                    "CERT_WRONG", TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, signature);

            assertFalse(valid);
        }

        @Test
        @DisplayName("使用正确证书类型验证强签名")
        void shouldVerifyStrongSignatureWithCorrectType() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_EXCELLENCE);

            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    signature, CertificateUtil.CERT_TYPE_EXCELLENCE);

            assertTrue(valid);
        }

        @Test
        @DisplayName("使用错误证书类型验证应该失败")
        void shouldFailVerificationWithWrongCertificateType() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    CertificateUtil.CERT_TYPE_EXCELLENCE);

            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, 
                    signature, CertificateUtil.CERT_TYPE_COMPLETION);

            assertFalse(valid);
        }

        @Test
        @DisplayName("null签名应该验证失败")
        void shouldFailVerificationForNullSignature() {
            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, null);

            assertFalse(valid);
        }

        @Test
        @DisplayName("长度不匹配的签名应该验证失败")
        void shouldFailVerificationForLengthMismatch() {
            String signature = certificateUtil.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            
            String shortSignature = signature.substring(0, 10);
            
            boolean valid = certificateUtil.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, shortSignature);

            assertFalse(valid);
        }
    }

    @Nested
    @DisplayName("有效期计算测试")
    class ValidityCalculationTests {

        @Test
        @DisplayName("普通证书有效期应该为3年")
        void completionCertificateShouldBeValidFor3Years() {
            int years = certificateUtil.getValidityYearsByType(CertificateUtil.CERT_TYPE_COMPLETION);
            assertEquals(3, years);
        }

        @Test
        @DisplayName("成就证书有效期应该为3年")
        void achievementCertificateShouldBeValidFor3Years() {
            int years = certificateUtil.getValidityYearsByType(CertificateUtil.CERT_TYPE_ACHIEVEMENT);
            assertEquals(3, years);
        }

        @Test
        @DisplayName("优秀证书有效期应该为5年")
        void excellenceCertificateShouldBeValidFor5Years() {
            int years = certificateUtil.getValidityYearsByType(CertificateUtil.CERT_TYPE_EXCELLENCE);
            assertEquals(5, years);
        }

        @Test
        @DisplayName("专业证书有效期应该为10年")
        void professionalCertificateShouldBeValidFor10Years() {
            int years = certificateUtil.getValidityYearsByType(CertificateUtil.CERT_TYPE_PROFESSIONAL);
            assertEquals(10, years);
        }

        @Test
        @DisplayName("应该正确计算有效期截止日期")
        void shouldCalculateValidUntilCorrectly() {
            LocalDateTime issuedAt = LocalDateTime.of(2026, 5, 11, 10, 0, 0);
            LocalDateTime validUntil = certificateUtil.calculateValidUntil(issuedAt, 3);

            assertEquals(2029, validUntil.getYear());
            assertEquals(5, validUntil.getMonthValue());
            assertEquals(11, validUntil.getDayOfMonth());
        }
    }

    @Nested
    @DisplayName("证书哈希测试")
    class CertificateHashTests {

        @Test
        @DisplayName("应该生成非空哈希")
        void shouldGenerateNonNullHash() {
            String hash = certificateUtil.generateCertificateHash(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, 
                    CertificateUtil.CERT_TYPE_COMPLETION);

            assertNotNull(hash);
            assertFalse(hash.isEmpty());
            assertEquals(64, hash.length());
        }

        @Test
        @DisplayName("相同输入应该生成相同哈希")
        void shouldGenerateSameHashForSameInput() {
            String hash1 = certificateUtil.generateCertificateHash(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, 
                    CertificateUtil.CERT_TYPE_COMPLETION);
            String hash2 = certificateUtil.generateCertificateHash(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, 
                    CertificateUtil.CERT_TYPE_COMPLETION);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("不同输入应该生成不同哈希")
        void shouldGenerateDifferentHashForDifferentInput() {
            String hash1 = certificateUtil.generateCertificateHash(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, 
                    CertificateUtil.CERT_TYPE_COMPLETION);
            String hash2 = certificateUtil.generateCertificateHash(
                    "CERT002", TEST_STUDENT_ID, TEST_COURSE_ID, 
                    CertificateUtil.CERT_TYPE_COMPLETION);

            assertNotEquals(hash1, hash2);
        }
    }

    @Nested
    @DisplayName("防伪造安全测试")
    class AntiForgerySecurityTests {

        @Test
        @DisplayName("不同密钥应该生成不同签名")
        void shouldGenerateDifferentSignatureWithDifferentKeys() {
            CertificateUtil util1 = new CertificateUtil();
            ReflectionTestUtils.setField(util1, "secretKey", "KEY_ONE");
            
            CertificateUtil util2 = new CertificateUtil();
            ReflectionTestUtils.setField(util2, "secretKey", "KEY_TWO");

            String sig1 = util1.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);
            String sig2 = util2.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);

            assertNotEquals(sig1, sig2);
        }

        @Test
        @DisplayName("使用错误密钥验证应该失败")
        void shouldFailVerificationWithWrongKey() {
            CertificateUtil signer = new CertificateUtil();
            ReflectionTestUtils.setField(signer, "secretKey", "CORRECT_KEY");

            CertificateUtil verifier = new CertificateUtil();
            ReflectionTestUtils.setField(verifier, "secretKey", "WRONG_KEY");

            String signature = signer.generateDigitalSignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT);

            boolean valid = verifier.verifySignature(
                    TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, signature);

            assertFalse(valid);
        }

        @Test
        @DisplayName("不能通过猜测的签名通过验证")
        void shouldRejectGuessedSignature() {
            String[] guesses = {
                "",
                "0000000000000000000000000000000000000000000000000000000000000000",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
            };

            for (String guess : guesses) {
                boolean valid = certificateUtil.verifySignature(
                        TEST_CERT_NUMBER, TEST_STUDENT_ID, TEST_COURSE_ID, TEST_ISSUED_AT, guess);
                assertFalse(valid, "猜测的签名不应该通过验证: " + guess);
            }
        }
    }
}
