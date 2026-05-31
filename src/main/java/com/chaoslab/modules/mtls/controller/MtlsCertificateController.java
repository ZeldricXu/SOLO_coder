package com.chaoslab.modules.mtls.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.MtlsRevocationList;
import com.chaoslab.entity.MtlsRotationPolicy;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.CertificateResponse;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.dto.RotationPolicyCreateRequest;
import com.chaoslab.modules.mtls.service.MtlsCertificateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mtls")
@RequiredArgsConstructor
public class MtlsCertificateController {

    private final MtlsCertificateService certificateService;

    @PostMapping("/policies")
    public Mono<ApiResponse<MtlsRotationPolicy>> createPolicy(
            @Valid @RequestBody RotationPolicyCreateRequest request) {
        return certificateService.createRotationPolicy(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/policies")
    public Mono<ApiResponse<List<MtlsRotationPolicy>>> listPolicies() {
        return certificateService.listRotationPolicies()
                .map(ApiResponse::success);
    }

    @PostMapping("/certificates")
    public Mono<ApiResponse<CertificateResponse>> issueCertificate(
            @Valid @RequestBody CertificateIssueRequest request) {
        return certificateService.issueCertificate(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/certificates/{certId}")
    public Mono<ApiResponse<CertificateResponse>> getCertificate(@PathVariable String certId) {
        return certificateService.getCertificate(certId)
                .map(ApiResponse::success);
    }

    @GetMapping("/certificates")
    public Mono<ApiResponse<List<CertificateResponse>>> listCertificates(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String commonName) {
        return certificateService.listCertificates(status, commonName)
                .map(ApiResponse::success);
    }

    @PostMapping("/certificates/revoke")
    public Mono<ApiResponse<MtlsRevocationList>> revokeCertificate(
            @Valid @RequestBody RevocationRequest request) {
        return certificateService.revokeCertificate(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/revocations")
    public Mono<ApiResponse<List<MtlsRevocationList>>> getRevocationList() {
        return certificateService.getRevocationList()
                .map(ApiResponse::success);
    }

    @GetMapping(value = "/crl", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> getCrl() {
        return certificateService.getCrl();
    }

    @PostMapping("/rotate")
    public Flux<ApiResponse<CertificateResponse>> rotateCertificates() {
        return certificateService.rotateExpiringCertificates()
                .map(ApiResponse::success);
    }
}
