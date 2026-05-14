package com.recruitment.service;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.common.enums.ResumeSource;
import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.dto.ResumeScreenRequest;
import com.recruitment.dto.ResumeSubmitRequest;
import com.recruitment.dto.ResumeSubmitResponse;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Candidate;
import com.recruitment.model.Position;
import com.recruitment.model.Resume;
import com.recruitment.repository.ResumeRepository;
import com.recruitment.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {
    private final ResumeRepository resumeRepository;
    private final PositionService positionService;
    private final CandidateService candidateService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final WorkflowService workflowService;
    private final ResumeCheckService resumeCheckService;

    @Transactional
    public ResumeSubmitResponse submitResume(ResumeSubmitRequest request) {
        log.info("Resume: 开始处理简历投递, positionId: {}", request.getPositionId());

        positionService.validatePositionForApplication(request.getPositionId());

        Position position = positionService.getPosition(request.getPositionId());

        Candidate candidate = candidateService.createOrGetCandidate(
                request.getCandidateName(),
                request.getCandidatePhone(),
                request.getCandidateEmail(),
                request.getCandidateEducation(),
                request.getCandidateExperience()
        );

        resumeCheckService.validateResumeSubmission(
                request.getPositionId(),
                candidate.getCandidateId()
        );

        String resumeId = IdGenerator.generateResumeId();
        Resume resume = Resume.builder()
                .resumeId(resumeId)
                .positionId(request.getPositionId())
                .candidateId(candidate.getCandidateId())
                .resumeStatus(ResumeStatus.PENDING_SCREEN)
                .resumeSource(parseResumeSource(request.getResumeSource()))
                .build();

        resumeRepository.save(resume);

        positionService.incrementResumeCount(request.getPositionId());

        candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.APPLIED);

        analysisService.incrementResumeCount();

        historyService.recordResumeSubmit(resumeId, request.getPositionId(), candidate.getCandidateId());

        log.info("Resume: 简历投递成功, resumeId: {}, candidateId: {}", resumeId, candidate.getCandidateId());

        return ResumeSubmitResponse.builder()
                .resumeId(resumeId)
                .status(ResumeStatus.PENDING_SCREEN.name())
                .candidateId(candidate.getCandidateId())
                .build();
    }

    @Transactional
    public ResumeScreenRequest.ResumeCheckResult checkResumeBeforeSubmit(ResumeSubmitRequest request) {
        log.info("Resume: 投递前检查, positionId: {}", request.getPositionId());

        Candidate candidate = candidateService.createOrGetCandidate(
                request.getCandidateName(),
                request.getCandidatePhone(),
                request.getCandidateEmail(),
                request.getCandidateEducation(),
                request.getCandidateExperience()
        );

        ResumeCheckService.ResumeCheckResult checkResult = resumeCheckService.performFullCheck(
                request.getPositionId(),
                candidate.getCandidateId()
        );

        return ResumeScreenRequest.ResumeCheckResult.builder()
                .passed(checkResult.isPassed())
                .positionStatusValid(checkResult.isPositionStatusValid())
                .duplicateCheckPassed(checkResult.isDuplicateCheckPassed())
                .availabilityCheckPassed(checkResult.isAvailabilityCheckPassed())
                .historyCheckPassed(checkResult.isHistoryCheckPassed())
                .errorMessage(checkResult.getErrorMessage())
                .build();
    }

    @Transactional
    public Resume screenResume(ResumeScreenRequest request) {
        log.info("Resume: 开始处理简历筛选, resumeId: {}", request.getResumeId());

        Resume resume = getResume(request.getResumeId());

        if (resume.getResumeStatus() != ResumeStatus.PENDING_SCREEN) {
            if (resume.getResumeStatus() == ResumeStatus.SCREENED) {
                throw new RuntimeException("简历已筛选");
            }
            throw new RuntimeException("简历状态不允许筛选: " + resume.getResumeStatus());
        }

        Position position = positionService.getPosition(resume.getPositionId());
        Candidate candidate = candidateService.getCandidate(resume.getCandidateId());

        String oldStatus = resume.getResumeStatus().name();
        boolean passed = request.getPassed() != null ? request.getPassed() :
                workflowService.evaluateScreenRules(
                        candidate.getCandidateEducation(),
                        candidate.getCandidateExperience(),
                        position.getPositionRequirement()
                );

        String description;
        if (passed) {
            resume.setResumeStatus(ResumeStatus.SCREENED);
            resume.setScreenedAt(Instant.now());
            resume.setScreenResult(request.getScreenResult() != null ? request.getScreenResult() : "通过筛选");
            description = "简历筛选通过";

            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.SCREENED);
            analysisService.incrementScreenedCount();
        } else {
            resume.setResumeStatus(ResumeStatus.REJECTED_SCREEN);
            resume.setScreenedAt(Instant.now());
            resume.setRejectReason(request.getRejectReason() != null ? request.getRejectReason() : "简历筛选未通过");
            description = "简历筛选淘汰: " + resume.getRejectReason();

            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.REJECTED);
            analysisService.incrementRejectCount();
        }

        Resume saved = resumeRepository.save(resume);

        historyService.recordResumeScreen(
                resume.getResumeId(),
                resume.getPositionId(),
                resume.getCandidateId(),
                oldStatus,
                resume.getResumeStatus().name(),
                description
        );

        log.info("Resume: 简历筛选完成, resumeId: {}, passed: {}", resume.getResumeId(), passed);

        return saved;
    }

    @Transactional
    public Resume updateResumeStatus(String resumeId, ResumeStatus newStatus) {
        Resume resume = getResume(resumeId);
        String oldStatus = resume.getResumeStatus().name();
        resume.setResumeStatus(newStatus);
        Resume saved = resumeRepository.save(resume);

        log.info("Resume: 更新简历状态, resumeId: {}, 状态: {} -> {}", resumeId, oldStatus, newStatus);

        return saved;
    }

    public Resume getResume(String resumeId) {
        return resumeRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new RuntimeException("简历不存在: " + resumeId));
    }

    public List<Resume> getAllResumes() {
        return resumeRepository.findAll();
    }

    public List<Resume> getResumesByPosition(String positionId) {
        return resumeRepository.findByPositionId(positionId);
    }

    public List<Resume> getResumesByCandidate(String candidateId) {
        return resumeRepository.findByCandidateId(candidateId);
    }

    public List<Resume> getResumesByStatus(ResumeStatus status) {
        return resumeRepository.findByResumeStatus(status);
    }

    public List<Resume> getPendingScreenResumes() {
        return resumeRepository.findByResumeStatus(ResumeStatus.PENDING_SCREEN);
    }

    public List<Resume> getScreenedResumes(String positionId) {
        return resumeRepository.findByPositionIdAndResumeStatus(positionId, ResumeStatus.SCREENED);
    }

    public List<Resume> getCandidateActiveResumes(String candidateId) {
        return resumeCheckService.getCandidateActiveResumes(candidateId);
    }

    public boolean canApplyToOtherPositions(String candidateId, String excludePositionId) {
        return resumeCheckService.canApplyToOtherPositions(candidateId, excludePositionId);
    }

    private ResumeSource parseResumeSource(String source) {
        if (source == null || source.isEmpty()) {
            return ResumeSource.PLATFORM;
        }
        try {
            return ResumeSource.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResumeSource.PLATFORM;
        }
    }
}
