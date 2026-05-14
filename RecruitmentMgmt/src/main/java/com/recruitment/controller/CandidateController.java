package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.model.Candidate;
import com.recruitment.service.CandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
public class CandidateController {
    private final CandidateService candidateService;

    @PostMapping
    public ResponseEntity<ApiResponse<Candidate>> createCandidate(
            @RequestParam String name,
            @RequestParam String phone,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String education,
            @RequestParam(required = false) String experience) {
        log.info("API: 创建候选人, name: {}", name);
        Candidate candidate = candidateService.createOrGetCandidate(name, phone, email, education, experience);
        return ResponseEntity.ok(ApiResponse.success("候选人创建成功", candidate));
    }

    @PutMapping("/{candidateId}/status")
    public ResponseEntity<ApiResponse<Candidate>> updateCandidateStatus(
            @PathVariable String candidateId,
            @RequestParam CandidateStatus status) {
        log.info("API: 更新候选人状态, candidateId: {}, status: {}", candidateId, status);
        Candidate candidate = candidateService.updateCandidateStatus(candidateId, status);
        return ResponseEntity.ok(ApiResponse.success("候选人状态更新成功", candidate));
    }

    @PutMapping("/{candidateId}")
    public ResponseEntity<ApiResponse<Candidate>> updateCandidate(
            @PathVariable String candidateId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String education,
            @RequestParam(required = false) String experience) {
        log.info("API: 更新候选人信息, candidateId: {}", candidateId);
        Candidate candidate = candidateService.updateCandidate(candidateId, name, email, education, experience);
        return ResponseEntity.ok(ApiResponse.success("候选人信息更新成功", candidate));
    }

    @GetMapping("/{candidateId}")
    public ResponseEntity<ApiResponse<Candidate>> getCandidate(@PathVariable String candidateId) {
        Candidate candidate = candidateService.getCandidate(candidateId);
        return ResponseEntity.ok(ApiResponse.success(candidate));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Candidate>>> getAllCandidates() {
        List<Candidate> candidates = candidateService.getAllCandidates();
        return ResponseEntity.ok(ApiResponse.success(candidates));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Candidate>>> getCandidatesByStatus(
            @PathVariable CandidateStatus status) {
        List<Candidate> candidates = candidateService.getCandidatesByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(candidates));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<Candidate>>> searchCandidates(@RequestParam String name) {
        List<Candidate> candidates = candidateService.searchCandidatesByName(name);
        return ResponseEntity.ok(ApiResponse.success(candidates));
    }

    @GetMapping("/phone/{phone}")
    public ResponseEntity<ApiResponse<Candidate>> findCandidateByPhone(@PathVariable String phone) {
        return candidateService.findCandidateByPhone(phone)
                .map(candidate -> ResponseEntity.ok(ApiResponse.success(candidate)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "候选人不存在")));
    }
}
