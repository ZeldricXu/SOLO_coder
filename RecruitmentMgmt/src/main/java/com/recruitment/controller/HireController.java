package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.HireStatus;
import com.recruitment.dto.HireApproveRequest;
import com.recruitment.dto.HireApproveResponse;
import com.recruitment.model.Hire;
import com.recruitment.service.HireApprovalWorkerService;
import com.recruitment.service.HireService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/hires")
@RequiredArgsConstructor
public class HireController {
    private final HireService hireService;
    private final HireApprovalWorkerService hireApprovalWorkerService;

    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<HireApproveResponse>> approveHire(
            @Valid @RequestBody HireApproveRequest request) {
        log.info("API: 接收录用审批请求, resumeId: {}", request.getResumeId());
        HireApproveResponse response = hireService.approveHire(request);
        return ResponseEntity.ok(ApiResponse.success("录用审批完成", response));
    }

    @PostMapping("/approve-async")
    public ResponseEntity<ApiResponse<HireApproveResponse>> approveHireAsync(
            @Valid @RequestBody HireApproveRequest request) {
        log.info("API: 接收异步录用审批请求, resumeId: {}", request.getResumeId());
        HireApproveResponse response = hireApprovalWorkerService.initiateAsyncApproval(request);
        return ResponseEntity.ok(ApiResponse.success("异步录用审批已启动，请稍后查询结果", response));
    }

    @PostMapping("/{hireId}/confirm")
    public ResponseEntity<ApiResponse<Hire>> confirmHire(@PathVariable String hireId) {
        log.info("API: 接收录用确认请求, hireId: {}", hireId);
        Hire hire = hireService.confirmHire(hireId);
        return ResponseEntity.ok(ApiResponse.success("录用确认成功", hire));
    }

    @GetMapping("/{hireId}")
    public ResponseEntity<ApiResponse<Hire>> getHire(@PathVariable String hireId) {
        Hire hire = hireService.getHire(hireId);
        return ResponseEntity.ok(ApiResponse.success(hire));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Hire>>> getAllHires() {
        List<Hire> hires = hireService.getAllHires();
        return ResponseEntity.ok(ApiResponse.success(hires));
    }

    @GetMapping("/resume/{resumeId}")
    public ResponseEntity<ApiResponse<Hire>> getHireByResume(@PathVariable String resumeId) {
        Hire hire = hireService.getHireByResume(resumeId);
        return ResponseEntity.ok(ApiResponse.success(hire));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Hire>>> getHiresByStatus(
            @PathVariable HireStatus status) {
        List<Hire> hires = hireService.getHiresByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(hires));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Hire>>> getPendingApprovalHires() {
        List<Hire> hires = hireService.getPendingApprovalHires();
        return ResponseEntity.ok(ApiResponse.success(hires));
    }

    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<ApiResponse<List<Hire>>> getHiresByCandidate(
            @PathVariable String candidateId) {
        List<Hire> hires = hireService.getHiresByCandidate(candidateId);
        return ResponseEntity.ok(ApiResponse.success(hires));
    }

    @GetMapping("/approval-stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getApprovalStats() {
        log.info("API: 获取异步审批统计");
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalApprovalCount", hireApprovalWorkerService.getTotalApprovalCount());
        stats.put("successCount", hireApprovalWorkerService.getSuccessCount());
        stats.put("failCount", hireApprovalWorkerService.getFailCount());
        stats.put("totalRetryCount", hireApprovalWorkerService.getTotalRetryCount());
        stats.put("maxRetryTimes", HireApprovalWorkerService.MAX_RETRY_TIMES);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/{hireId}/retry-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRetryCount(@PathVariable String hireId) {
        log.info("API: 获取录用审批重试次数, hireId: {}", hireId);
        Map<String, Object> result = new HashMap<>();
        result.put("hireId", hireId);
        result.put("retryCount", hireApprovalWorkerService.getRetryCount(hireId));
        result.put("maxRetryTimes", HireApprovalWorkerService.MAX_RETRY_TIMES);
        result.put("hasMaxRetryReached", hireApprovalWorkerService.hasMaxRetryReached(hireId));
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
