
package com.learningplatform.controller;

import com.learningplatform.dto.ApiResponse;
import com.learningplatform.dto.GenerateCertificateRequest;
import com.learningplatform.dto.GenerateCertificateResponse;
import com.learningplatform.entity.Certificate;
import com.learningplatform.service.CertificateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/certificates")
public class CertificateController {

    @Autowired
    private CertificateService certificateService;

    @PostMapping("/generate")
    public ApiResponse<GenerateCertificateResponse> generateCertificate(
            @Validated @RequestBody GenerateCertificateRequest request) {
        GenerateCertificateResponse response = certificateService.generateCertificate(
                request.getCourseId(),
                request.getStudentId()
        );
        return ApiResponse.success(response);
    }

    @GetMapping("/{certificateId}")
    public ApiResponse<Certificate> getCertificateById(@PathVariable String certificateId) {
        Certificate certificate = certificateService.getCertificateById(certificateId);
        return ApiResponse.success(certificate);
    }

    @GetMapping("/number/{certificateNumber}")
    public ApiResponse<Certificate> getCertificateByNumber(@PathVariable String certificateNumber) {
        Certificate certificate = certificateService.getCertificateByNumber(certificateNumber);
        return ApiResponse.success(certificate);
    }

    @GetMapping("/verify/{certificateNumber}")
    public ApiResponse<Map<String, Object>> verifyCertificate(@PathVariable String certificateNumber) {
        boolean valid = certificateService.verifyCertificate(certificateNumber);
        Map<String, Object> result = new HashMap<>();
        result.put("valid", valid);
        result.put("certificateNumber", certificateNumber);
        result.put("message", valid ? "证书验证通过" : "证书验证失败");
        return ApiResponse.success(result);
    }

    @GetMapping("/student/{studentId}")
    public ApiResponse<List<Certificate>> getStudentCertificates(@PathVariable String studentId) {
        List<Certificate> certificates = certificateService.getStudentCertificates(studentId);
        return ApiResponse.success(certificates);
    }

    @GetMapping("/course/{courseId}")
    public ApiResponse<List<Certificate>> getCourseCertificates(@PathVariable String courseId) {
        List<Certificate> certificates = certificateService.getCourseCertificates(courseId);
        return ApiResponse.success(certificates);
    }
}
