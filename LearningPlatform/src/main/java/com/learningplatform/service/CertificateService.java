
package com.learningplatform.service;

import com.learningplatform.dto.GenerateCertificateResponse;
import com.learningplatform.entity.Certificate;
import com.learningplatform.entity.Progress;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.CertificateRepository;
import com.learningplatform.repository.ProgressRepository;
import com.learningplatform.util.CertificateUtil;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CertificateService {

    private static final Logger logger = LoggerFactory.getLogger(CertificateService.class);

    @Autowired
    private CertificateRepository certificateRepository;

    @Autowired
    private ProgressRepository progressRepository;

    @Autowired
    private CourseService courseService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private CertificateUtil certificateUtil;

    @Transactional
    public GenerateCertificateResponse generateCertificate(String courseId, String studentId) {
        return generateCertificate(courseId, studentId, CertificateUtil.CERT_TYPE_COMPLETION);
    }

    @Transactional
    public GenerateCertificateResponse generateCertificate(String courseId, String studentId, String certificateType) {
        courseService.getCourseById(courseId);
        studentService.getStudentById(studentId);

        if (certificateRepository.existsByCourseIdAndStudentId(courseId, studentId)) {
            Certificate existing = certificateRepository.findByCourseIdAndStudentId(courseId, studentId).get();
            logger.info("证书已存在: student={}, course={}, cert={}", studentId, courseId, existing.getCertificateId());
            return new GenerateCertificateResponse(existing.getCertificateId(), existing.getCertificateNumber(), existing.getCertificateStatus());
        }

        Optional<Progress> progressOpt = progressRepository.findByCourseIdAndStudentId(courseId, studentId);
        if (progressOpt.isEmpty()) {
            throw new BusinessException(400, "未找到学习记录");
        }

        Progress progress = progressOpt.get();
        if (!"completed".equals(progress.getProgressStatus())) {
            throw new BusinessException(400, "课程未完成，无法生成证书");
        }

        LocalDateTime issuedAt = LocalDateTime.now();
        String certificateNumber = IdGenerator.generateCertificateNumber();
        String digitalSignature = certificateUtil.generateDigitalSignature(
                certificateNumber, studentId, courseId, issuedAt, certificateType);

        Certificate certificate = new Certificate();
        certificate.setCertificateId(IdGenerator.generateCertificateId());
        certificate.setCourseId(courseId);
        certificate.setStudentId(studentId);
        certificate.setCertificateType(certificateType);
        certificate.setCertificateNumber(certificateNumber);
        certificate.setCertificateStatus("valid");
        certificate.setDigitalSignature(digitalSignature);
        certificate.setIssuedAt(issuedAt);
        certificate.setValidUntil(certificateUtil.calculateValidUntil(
                issuedAt, certificateUtil.getValidityYearsByType(certificateType)));

        Certificate saved = certificateRepository.save(certificate);

        studentService.incrementCertificatesEarned(studentId);
        analysisService.incrementCertificateCount();
        historyService.recordCertificateGenerate(studentId, courseId, saved.getCertificateId(), certificateNumber);

        logger.info("生成证书成功: cert={}, number={}, type={}, student={}, course={}", 
                saved.getCertificateId(), certificateNumber, certificateType, studentId, courseId);

        return new GenerateCertificateResponse(saved.getCertificateId(), certificateNumber, "valid");
    }

    public Certificate getCertificateById(String certificateId) {
        return certificateRepository.findById(certificateId)
                .orElseThrow(() -> new BusinessException(404, "证书不存在: " + certificateId));
    }

    public Certificate getCertificateByNumber(String certificateNumber) {
        return certificateRepository.findByCertificateNumber(certificateNumber)
                .orElseThrow(() -> new BusinessException(404, "证书不存在: " + certificateNumber));
    }

    public Optional<Certificate> getCertificateByCourseAndStudent(String courseId, String studentId) {
        return certificateRepository.findByCourseIdAndStudentId(courseId, studentId);
    }

    public List<Certificate> getStudentCertificates(String studentId) {
        return certificateRepository.findByStudentId(studentId);
    }

    public List<Certificate> getCourseCertificates(String courseId) {
        return certificateRepository.findByCourseId(courseId);
    }

    public boolean verifyCertificate(String certificateNumber) {
        try {
            Certificate certificate = getCertificateByNumber(certificateNumber);
            return verifyCertificate(certificate);
        } catch (BusinessException e) {
            logger.warn("证书验证失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean verifyCertificate(Certificate certificate) {
        if (!"valid".equals(certificate.getCertificateStatus())) {
            logger.warn("证书状态无效: number={}, status={}", 
                    certificate.getCertificateNumber(), certificate.getCertificateStatus());
            return false;
        }

        if (certificate.getValidUntil() != null && LocalDateTime.now().isAfter(certificate.getValidUntil())) {
            logger.warn("证书已过期: number={}", certificate.getCertificateNumber());
            return false;
        }

        boolean signatureValid = certificateUtil.verifySignature(
                certificate.getCertificateNumber(),
                certificate.getStudentId(),
                certificate.getCourseId(),
                certificate.getIssuedAt(),
                certificate.getDigitalSignature(),
                certificate.getCertificateType());

        if (!signatureValid) {
            logger.warn("证书签名验证失败: number={}", certificate.getCertificateNumber());
            return false;
        }

        logger.info("证书验证通过: number={}", certificate.getCertificateNumber());
        return true;
    }

    @Transactional
    public Certificate revokeCertificate(String certificateId) {
        Certificate certificate = getCertificateById(certificateId);
        certificate.setCertificateStatus("revoked");
        Certificate saved = certificateRepository.save(certificate);
        logger.info("吊销证书: cert={}", certificateId);
        return saved;
    }

    @Transactional
    public Certificate expireCertificate(String certificateId) {
        Certificate certificate = getCertificateById(certificateId);
        certificate.setCertificateStatus("expired");
        Certificate saved = certificateRepository.save(certificate);
        logger.info("过期证书: cert={}", certificateId);
        return saved;
    }

    @Transactional
    public Certificate reinstateCertificate(String certificateId) {
        Certificate certificate = getCertificateById(certificateId);
        if (certificate.getValidUntil() != null && LocalDateTime.now().isAfter(certificate.getValidUntil())) {
            throw new BusinessException(400, "证书已过有效期，无法恢复");
        }
        certificate.setCertificateStatus("valid");
        Certificate saved = certificateRepository.save(certificate);
        logger.info("恢复证书: cert={}", certificateId);
        return saved;
    }

    public long getTotalCertificateCount() {
        return certificateRepository.count();
    }
}
