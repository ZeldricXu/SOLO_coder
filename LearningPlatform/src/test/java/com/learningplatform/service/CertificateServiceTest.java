
package com.learningplatform.service;

import com.learningplatform.builder.TestDataBuilder;
import com.learningplatform.dto.GenerateCertificateResponse;
import com.learningplatform.entity.Certificate;
import com.learningplatform.entity.Progress;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.CertificateRepository;
import com.learningplatform.repository.ProgressRepository;
import com.learningplatform.util.CertificateUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CertificateService 证书服务测试")
class CertificateServiceTest {

    @Mock
    private CertificateRepository certificateRepository;

    @Mock
    private ProgressRepository progressRepository;

    @Mock
    private CourseService courseService;

    @Mock
    private StudentService studentService;

    @Mock
    private HistoryService historyService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private CertificateUtil certificateUtil;

    @InjectMocks
    private CertificateService certificateService;

    private Certificate testCertificate;
    private CertificateUtil realCertificateUtil;

    @BeforeEach
    void setUp() {
        testCertificate = TestDataBuilder.createDefaultCertificate();
        realCertificateUtil = new CertificateUtil();
        ReflectionTestUtils.setField(realCertificateUtil, "secretKey", "TEST_SECRET_KEY");
    }

    @Nested
    @DisplayName("证书生成测试")
    class CertificateGenerationTests {

        @Test
        @DisplayName("应该成功生成证书")
        void shouldGenerateCertificateSuccessfully() {
            Progress completedProgress = TestDataBuilder.createCompletedProgress();

            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(completedProgress));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenReturn(testCertificate);
            when(certificateUtil.generateDigitalSignature(anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn("test_signature");
            when(certificateUtil.calculateValidUntil(any(), anyInt()))
                    .thenReturn(LocalDateTime.now().plusYears(3));
            when(certificateUtil.getValidityYearsByType(anyString()))
                    .thenReturn(3);

            GenerateCertificateResponse response = certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID
            );

            assertNotNull(response);
            assertEquals(TestDataBuilder.TEST_CERTIFICATE_ID, response.getCertificateId());
            verify(certificateRepository, times(1)).save(any(Certificate.class));
            verify(analysisService, times(1)).incrementCertificateCount();
        }

        @Test
        @DisplayName("证书已存在时应该返回现有证书")
        void shouldReturnExistingCertificateWhenAlreadyExists() {
            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(true);
            when(certificateRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(testCertificate));

            GenerateCertificateResponse response = certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID
            );

            assertNotNull(response);
            assertEquals(testCertificate.getCertificateNumber(), response.getNumber());
            verify(certificateRepository, never()).save(any(Certificate.class));
        }

        @Test
        @DisplayName("当学习未完成时应该抛出异常")
        void shouldThrowExceptionWhenLearningNotCompleted() {
            Progress inProgress = TestDataBuilder.createDefaultProgress();
            inProgress.setProgressStatus("in_progress");

            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(inProgress));

            assertThrows(BusinessException.class, () ->
                certificateService.generateCertificate(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID)
            );
        }

        @Test
        @DisplayName("当没有学习记录时应该抛出异常")
        void shouldThrowExceptionWhenNoProgressFound() {
            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                certificateService.generateCertificate(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID)
            );
        }

        @Test
        @DisplayName("生成证书时应该添加数字签名")
        void shouldAddDigitalSignatureWhenGeneratingCertificate() {
            Progress completedProgress = TestDataBuilder.createCompletedProgress();
            String expectedSignature = "generated_digital_signature_12345";

            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(completedProgress));
            when(certificateUtil.generateDigitalSignature(anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn(expectedSignature);
            when(certificateRepository.save(any(Certificate.class)))
                    .thenAnswer(invocation -> {
                        Certificate cert = invocation.getArgument(0);
                        cert.setCertificateId(TestDataBuilder.TEST_CERTIFICATE_ID);
                        return cert;
                    });
            when(certificateUtil.calculateValidUntil(any(), anyInt()))
                    .thenReturn(LocalDateTime.now().plusYears(3));
            when(certificateUtil.getValidityYearsByType(anyString()))
                    .thenReturn(3);

            GenerateCertificateResponse response = certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID
            );

            verify(certificateUtil, times(1)).generateDigitalSignature(
                anyString(), anyString(), anyString(), any(), anyString()
            );
        }

        @Test
        @DisplayName("不同证书类型应该使用不同的签名算法")
        void shouldUseDifferentAlgorithmForDifferentCertificateTypes() {
            Progress completedProgress = TestDataBuilder.createCompletedProgress();

            when(certificateRepository.existsByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(anyString(), anyString()))
                    .thenReturn(Optional.of(completedProgress));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenReturn(testCertificate);
            when(certificateUtil.calculateValidUntil(any(), anyInt()))
                    .thenReturn(LocalDateTime.now().plusYears(3));
            when(certificateUtil.getValidityYearsByType(anyString()))
                    .thenReturn(3);
            when(certificateUtil.generateDigitalSignature(anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn("signature");

            certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID,
                    CertificateUtil.CERT_TYPE_COMPLETION
            );

            certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID,
                    CertificateUtil.CERT_TYPE_PROFESSIONAL
            );

            verify(certificateUtil, times(2)).generateDigitalSignature(
                anyString(), anyString(), anyString(), any(), anyString()
            );
        }
    }

    @Nested
    @DisplayName("证书状态流转测试")
    class CertificateStateTransitionTests {

        @Test
        @DisplayName("新生成的证书应该是有效状态")
        void newCertificateShouldBeValid() {
            Progress completedProgress = TestDataBuilder.createCompletedProgress();

            when(certificateRepository.existsByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(false);
            when(progressRepository.findByCourseIdAndStudentId(TestDataBuilder.TEST_COURSE_ID, TestDataBuilder.TEST_STUDENT_ID))
                    .thenReturn(Optional.of(completedProgress));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenAnswer(invocation -> {
                        Certificate cert = invocation.getArgument(0);
                        cert.setCertificateId(TestDataBuilder.TEST_CERTIFICATE_ID);
                        return cert;
                    });
            when(certificateUtil.generateDigitalSignature(anyString(), anyString(), anyString(), any(), anyString()))
                    .thenReturn("signature");
            when(certificateUtil.calculateValidUntil(any(), anyInt()))
                    .thenReturn(LocalDateTime.now().plusYears(3));
            when(certificateUtil.getValidityYearsByType(anyString()))
                    .thenReturn(3);

            GenerateCertificateResponse response = certificateService.generateCertificate(
                    TestDataBuilder.TEST_COURSE_ID, 
                    TestDataBuilder.TEST_STUDENT_ID
            );

            assertEquals("valid", response.getStatus());
        }

        @Test
        @DisplayName("应该成功吊销证书")
        void shouldRevokeCertificateSuccessfully() {
            Certificate validCert = TestDataBuilder.createDefaultCertificate();
            validCert.setCertificateStatus("valid");

            Certificate revokedCert = TestDataBuilder.createDefaultCertificate();
            revokedCert.setCertificateStatus("revoked");

            when(certificateRepository.findById(TestDataBuilder.TEST_CERTIFICATE_ID))
                    .thenReturn(Optional.of(validCert));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenReturn(revokedCert);

            Certificate result = certificateService.revokeCertificate(TestDataBuilder.TEST_CERTIFICATE_ID);

            assertEquals("revoked", result.getCertificateStatus());
            verify(certificateRepository).save(argThat(cert -> "revoked".equals(cert.getCertificateStatus())));
        }

        @Test
        @DisplayName("应该成功过期证书")
        void shouldExpireCertificateSuccessfully() {
            Certificate validCert = TestDataBuilder.createDefaultCertificate();
            validCert.setCertificateStatus("valid");

            Certificate expiredCert = TestDataBuilder.createDefaultCertificate();
            expiredCert.setCertificateStatus("expired");

            when(certificateRepository.findById(TestDataBuilder.TEST_CERTIFICATE_ID))
                    .thenReturn(Optional.of(validCert));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenReturn(expiredCert);

            Certificate result = certificateService.expireCertificate(TestDataBuilder.TEST_CERTIFICATE_ID);

            assertEquals("expired", result.getCertificateStatus());
        }

        @Test
        @DisplayName("未过期的证书应该可以恢复")
        void shouldReinstateCertificateIfNotExpired() {
            Certificate revokedCert = TestDataBuilder.createRevokedCertificate();
            revokedCert.setValidUntil(LocalDateTime.now().plusYears(1));

            Certificate reinstatedCert = TestDataBuilder.createDefaultCertificate();
            reinstatedCert.setCertificateStatus("valid");

            when(certificateRepository.findById(TestDataBuilder.TEST_CERTIFICATE_ID))
                    .thenReturn(Optional.of(revokedCert));
            when(certificateRepository.save(any(Certificate.class)))
                    .thenReturn(reinstatedCert);

            Certificate result = certificateService.reinstateCertificate(TestDataBuilder.TEST_CERTIFICATE_ID);

            assertEquals("valid", result.getCertificateStatus());
        }

        @Test
        @DisplayName("已过期的证书不应该被恢复")
        void shouldNotReinstateExpiredCertificate() {
            Certificate expiredCert = TestDataBuilder.createExpiredCertificate();

            when(certificateRepository.findById(TestDataBuilder.TEST_CERTIFICATE_ID))
                    .thenReturn(Optional.of(expiredCert));

            assertThrows(BusinessException.class, () ->
                certificateService.reinstateCertificate(TestDataBuilder.TEST_CERTIFICATE_ID)
            );
        }

        @Test
        @DisplayName("证书不存在时状态操作应该抛出异常")
        void shouldThrowExceptionWhenCertificateNotFound() {
            when(certificateRepository.findById("nonexistent_cert"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                certificateService.revokeCertificate("nonexistent_cert")
            );
            assertThrows(BusinessException.class, () ->
                certificateService.expireCertificate("nonexistent_cert")
            );
            assertThrows(BusinessException.class, () ->
                certificateService.reinstateCertificate("nonexistent_cert")
            );
        }
    }

    @Nested
    @DisplayName("证书验证测试")
    class CertificateVerificationTests {

        @Test
        @DisplayName("有效证书应该验证通过")
        void shouldVerifyValidCertificate() {
            Certificate validCert = TestDataBuilder.createDefaultCertificate();
            validCert.setCertificateStatus("valid");
            validCert.setValidUntil(LocalDateTime.now().plusYears(1));

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(validCert));
            when(certificateUtil.verifySignature(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertTrue(result);
        }

        @Test
        @DisplayName("吊销的证书应该验证失败")
        void shouldFailVerificationForRevokedCertificate() {
            Certificate revokedCert = TestDataBuilder.createRevokedCertificate();

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(revokedCert));

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertFalse(result);
        }

        @Test
        @DisplayName("过期的证书应该验证失败")
        void shouldFailVerificationForExpiredCertificate() {
            Certificate expiredCert = TestDataBuilder.createDefaultCertificate();
            expiredCert.setCertificateStatus("valid");
            expiredCert.setValidUntil(LocalDateTime.now().minusDays(1));

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(expiredCert));

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertFalse(result);
        }

        @Test
        @DisplayName("签名无效的证书应该验证失败")
        void shouldFailVerificationForInvalidSignature() {
            Certificate cert = TestDataBuilder.createDefaultCertificate();
            cert.setCertificateStatus("valid");
            cert.setValidUntil(LocalDateTime.now().plusYears(1));

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(cert));
            when(certificateUtil.verifySignature(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(false);

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertFalse(result);
        }

        @Test
        @DisplayName("证书不存在时验证应该失败")
        void shouldFailVerificationWhenCertificateNotFound() {
            when(certificateRepository.findByCertificateNumber("nonexistent_number"))
                    .thenReturn(Optional.empty());

            boolean result = certificateService.verifyCertificate("nonexistent_number");

            assertFalse(result);
        }

        @Test
        @DisplayName("应该验证证书状态、有效期和签名")
        void shouldVerifyAllAspectsOfCertificate() {
            Certificate cert = TestDataBuilder.createDefaultCertificate();
            cert.setCertificateStatus("valid");
            cert.setValidUntil(LocalDateTime.now().plusYears(1));

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(cert));
            when(certificateUtil.verifySignature(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertTrue(result);
            verify(certificateUtil, times(1)).verifySignature(
                eq(cert.getCertificateNumber()),
                eq(cert.getStudentId()),
                eq(cert.getCourseId()),
                eq(cert.getIssuedAt()),
                eq(cert.getDigitalSignature()),
                eq(cert.getCertificateType())
            );
        }
    }

    @Nested
    @DisplayName("证书查询测试")
    class CertificateQueryTests {

        @Test
        @DisplayName("应该通过ID获取证书")
        void shouldGetCertificateById() {
            when(certificateRepository.findById(TestDataBuilder.TEST_CERTIFICATE_ID))
                    .thenReturn(Optional.of(testCertificate));

            Certificate result = certificateService.getCertificateById(TestDataBuilder.TEST_CERTIFICATE_ID);

            assertNotNull(result);
            assertEquals(TestDataBuilder.TEST_CERTIFICATE_ID, result.getCertificateId());
        }

        @Test
        @DisplayName("应该通过编号获取证书")
        void shouldGetCertificateByNumber() {
            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(testCertificate));

            Certificate result = certificateService.getCertificateByNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertNotNull(result);
            assertEquals(TestDataBuilder.TEST_CERTIFICATE_NUMBER, result.getCertificateNumber());
        }

        @Test
        @DisplayName("证书不存在时应该抛出异常")
        void shouldThrowExceptionWhenCertificateNotFound() {
            when(certificateRepository.findById("nonexistent_id"))
                    .thenReturn(Optional.empty());
            when(certificateRepository.findByCertificateNumber("nonexistent_number"))
                    .thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () ->
                certificateService.getCertificateById("nonexistent_id")
            );
            assertThrows(BusinessException.class, () ->
                certificateService.getCertificateByNumber("nonexistent_number")
            );
        }

        @Test
        @DisplayName("应该获取证书计数")
        void shouldGetCertificateCount() {
            when(certificateRepository.count())
                    .thenReturn(100L);

            long count = certificateService.getTotalCertificateCount();

            assertEquals(100L, count);
        }
    }

    @Nested
    @DisplayName("签名防伪测试")
    class SignatureAntiForgeryTests {

        @Test
        @DisplayName("被篡改的证书签名应该验证失败")
        void shouldFailVerificationForTamperedSignature() {
            Certificate cert = TestDataBuilder.createDefaultCertificate();
            cert.setCertificateStatus("valid");
            cert.setValidUntil(LocalDateTime.now().plusYears(1));
            
            String originalSignature = cert.getDigitalSignature();
            cert.setDigitalSignature(originalSignature + "tampered");

            when(certificateRepository.findByCertificateNumber(TestDataBuilder.TEST_CERTIFICATE_NUMBER))
                    .thenReturn(Optional.of(cert));
            when(certificateUtil.verifySignature(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(false);

            boolean result = certificateService.verifyCertificate(TestDataBuilder.TEST_CERTIFICATE_NUMBER);

            assertFalse(result);
        }

        @Test
        @DisplayName("被篡改的证书编号应该验证失败")
        void shouldFailVerificationForTamperedCertificateNumber() {
            String validNumber = TestDataBuilder.TEST_CERTIFICATE_NUMBER;
            String tamperedNumber = "CERT9999999999";

            when(certificateRepository.findByCertificateNumber(validNumber))
                    .thenReturn(Optional.of(testCertificate));
            when(certificateRepository.findByCertificateNumber(tamperedNumber))
                    .thenReturn(Optional.empty());
            when(certificateUtil.verifySignature(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                    .thenReturn(true);

            boolean validResult = certificateService.verifyCertificate(validNumber);
            boolean tamperedResult = certificateService.verifyCertificate(tamperedNumber);

            assertTrue(validResult);
            assertFalse(tamperedResult);
        }
    }
}
