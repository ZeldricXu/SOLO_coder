package com.recruitment.service;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.common.enums.HireStatus;
import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.dto.HireApproveRequest;
import com.recruitment.dto.HireApproveResponse;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Candidate;
import com.recruitment.model.Hire;
import com.recruitment.model.Resume;
import com.recruitment.repository.HireRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class HireApprovalWorkerService {

    private final HireRepository hireRepository;
    private final ResumeService resumeService;
    private final CandidateService candidateService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;

    private final Map<String, Integer> retryCountMap = new ConcurrentHashMap<>();
    private final AtomicInteger totalApprovalCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicInteger retryCount = new AtomicInteger(0);

    public static final int MAX_RETRY_TIMES = 3;
    public static final long RETRY_DELAY_MS = 1000;

    @Async("hireApprovalExecutor")
    public void processHireApprovalAsync(HireApproveRequest request) {
        log.info("HireApprovalWorker: 异步开始处理录用审批, resumeId: {}", request.getResumeId());

        boolean success = processWithRetry(request);

        if (success) {
            log.info("HireApprovalWorker: 异步录用审批处理完成, resumeId: {}", request.getResumeId());
        } else {
            log.error("HireApprovalWorker: 异步录用审批处理失败, 已达到最大重试次数, resumeId: {}",
                    request.getResumeId());
        }
    }

    public boolean processWithRetry(HireApproveRequest request) {
        String hireId = getHireIdFromResume(request.getResumeId());
        int retryTimes = 0;

        while (retryTimes < MAX_RETRY_TIMES) {
            try {
                doProcessApproval(request, hireId);
                successCount.incrementAndGet();
                totalApprovalCount.incrementAndGet();
                return true;
            } catch (Exception e) {
                retryTimes++;
                retryCountMap.put(hireId, retryTimes);
                retryCount.incrementAndGet();

                log.warn("HireApprovalWorker: 录用审批处理失败, 第 {} 次重试, hireId: {}, 错误: {}",
                        retryTimes, hireId, e.getMessage());

                if (retryTimes < MAX_RETRY_TIMES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * retryTimes);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("HireApprovalWorker: 重试等待被中断");
                    }
                }
            }
        }

        failCount.incrementAndGet();
        totalApprovalCount.incrementAndGet();
        return false;
    }

    private void doProcessApproval(HireApproveRequest request, String hireId) {
        log.info("HireApprovalWorker: 执行录用审批逻辑, hireId: {}", hireId);

        Hire hire = hireRepository.findByResumeId(request.getResumeId())
                .orElseThrow(() -> new RuntimeException("录用记录不存在: " + request.getResumeId()));

        if (hire.getHireStatus() != HireStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("录用状态不允许审批: " + hire.getHireStatus());
        }

        Resume resume = resumeService.getResume(hire.getResumeId());
        Candidate candidate = candidateService.getCandidate(hire.getCandidateId());

        String oldStatus = hire.getHireStatus().name();
        boolean approved = request.getApproved() != null ? request.getApproved() : true;

        String description;
        if (approved) {
            hire.setHireStatus(HireStatus.APPROVED);
            hire.setHireSalary(request.getHireSalary());
            hire.setHireDate(request.getHireDate() != null ? request.getHireDate() : LocalDate.now().plusDays(30));
            hire.setApprovedAt(Instant.now());
            description = "录用审批通过(异步)";

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.HIRED);
            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.HIRED);
            analysisService.incrementHireCount();
        } else {
            hire.setHireStatus(HireStatus.REJECTED);
            hire.setRejectReason("审批未通过(异步)");
            description = "录用审批未通过(异步)";

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.REJECTED);
            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.REJECTED);
            analysisService.incrementRejectCount();
        }

        hireRepository.save(hire);

        historyService.recordHireApprove(
                hire.getHireId(),
                hire.getResumeId(),
                resume.getPositionId(),
                hire.getCandidateId(),
                oldStatus,
                hire.getHireStatus().name(),
                description
        );

        log.info("HireApprovalWorker: 录用审批逻辑执行完成, hireId: {}, approved: {}", hireId, approved);
    }

    public HireApproveResponse initiateAsyncApproval(HireApproveRequest request) {
        log.info("HireApprovalWorker: 发起异步录用审批, resumeId: {}", request.getResumeId());

        Hire hire = hireRepository.findByResumeId(request.getResumeId())
                .orElseThrow(() -> new RuntimeException("录用记录不存在: " + request.getResumeId()));

        Candidate candidate = candidateService.getCandidate(hire.getCandidateId());

        processHireApprovalAsync(request);

        return HireApproveResponse.builder()
                .hireId(hire.getHireId())
                .status("PENDING_ASYNC_PROCESSING")
                .candidateName(candidate.getCandidateName())
                .build();
    }

    private String getHireIdFromResume(String resumeId) {
        return hireRepository.findByResumeId(resumeId)
                .map(Hire::getHireId)
                .orElseGet(() -> "unknown_" + resumeId);
    }

    public int getRetryCount(String hireId) {
        return retryCountMap.getOrDefault(hireId, 0);
    }

    public int getTotalApprovalCount() {
        return totalApprovalCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailCount() {
        return failCount.get();
    }

    public int getTotalRetryCount() {
        return retryCount.get();
    }

    public void resetCounters() {
        totalApprovalCount.set(0);
        successCount.set(0);
        failCount.set(0);
        retryCount.set(0);
        retryCountMap.clear();
    }

    public boolean hasMaxRetryReached(String hireId) {
        return getRetryCount(hireId) >= MAX_RETRY_TIMES;
    }
}
