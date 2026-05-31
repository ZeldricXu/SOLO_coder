package com.solocoder.dns.mtls.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.mtls.model.Certificate;
import com.solocoder.dns.mtls.model.CertificateRevocation;
import com.solocoder.dns.mtls.model.RotationPolicy;
import com.solocoder.dns.mtls.service.MtlsCertificateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mtls")
@RequiredArgsConstructor
public class MtlsController {
    private final MtlsCertificateService certService;

    @PostMapping("/certificates")
    public ApiResponse<Certificate> issueCertificate(@RequestBody Map<String, Object> request) {
        String commonName = (String) request.get("commonName");
        Integer validityDays = request.get("validityDays") != null ? ((Number) request.get("validityDays")).intValue() : 365;
        return ApiResponse.success(201, certService.issueCertificate(commonName, validityDays));
    }

    @GetMapping("/certificates")
    public ApiResponse<PageResult<Certificate>> listCertificates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(certService.listCertificates(page, size, status));
    }

    @GetMapping("/certificates/{id}")
    public ApiResponse<Certificate> getCertificate(@PathVariable String id) {
        return ApiResponse.success(certService.getCertificate(id));
    }

    @PostMapping("/certificates/{id}/rotate")
    public ApiResponse<Certificate> rotateCertificate(@PathVariable String id) {
        return ApiResponse.success(certService.rotateCertificate(id));
    }

    @PostMapping("/certificates/revoke")
    public ApiResponse<Void> revokeCertificate(@RequestBody Map<String, String> request) {
        certService.revokeCertificate(request.get("serialNumber"), request.get("reason"));
        return ApiResponse.success(null);
    }

    @GetMapping("/certificates/check/{serialNumber}")
    public ApiResponse<Map<String, Boolean>> checkRevocation(@PathVariable String serialNumber) {
        return ApiResponse.success(Map.of("revoked", certService.isRevoked(serialNumber)));
    }

    @GetMapping("/certificates/expiring/{days}")
    public ApiResponse<List<Certificate>> getExpiringCertificates(@PathVariable int days) {
        return ApiResponse.success(certService.getExpiringCertificates(days));
    }

    @PostMapping("/policies")
    public ApiResponse<RotationPolicy> createPolicy(@RequestBody RotationPolicy policy) {
        return ApiResponse.success(201, certService.createRotationPolicy(policy));
    }

    @GetMapping("/policies")
    public ApiResponse<List<RotationPolicy>> listPolicies() {
        return ApiResponse.success(certService.getAllRotationPolicies());
    }
}
