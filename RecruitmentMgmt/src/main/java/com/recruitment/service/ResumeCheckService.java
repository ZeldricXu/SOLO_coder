package com.recruitment.service;

import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.model.Position;
import com.recruitment.model.Resume;
import com.recruitment.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeCheckService {

    private final ResumeRepository resumeRepository;
    private final PositionService positionService;

    public void validateResumeSubmission(String positionId, String candidateId) {
        log.info("ResumeCheck: 验证简历投递, positionId: {}, candidateId: {}", positionId, candidateId);

        validatePositionStatus(positionId);
        validateDuplicateSubmission(positionId, candidateId);
        validateCandidateApplicationHistory(candidateId, positionId);
        validatePositionAvailability(positionId);

        log.info("ResumeCheck: 简历投递验证通过");
    }

    public void validatePositionStatus(String positionId) {
        log.debug("ResumeCheck: 验证职位状态, positionId: {}", positionId);
        positionService.validatePositionForApplication(positionId);
    }

    public void validateDuplicateSubmission(String positionId, String candidateId) {
        log.debug("ResumeCheck: 检查重复投递, positionId: {}, candidateId: {}", positionId, candidateId);

        boolean exists = resumeRepository.existsByPositionIdAndCandidateId(positionId, candidateId);
        if (exists) {
            log.warn("ResumeCheck: 检测到重复投递, positionId: {}, candidateId: {}", positionId, candidateId);
            throw new RuntimeException("已投递过该职位，请勿重复投递");
        }

        List<Resume> candidateResumes = resumeRepository.findByPositionIdAndCandidateIdList(positionId, candidateId);
        for (Resume resume : candidateResumes) {
            ResumeStatus status = resume.getResumeStatus();
            if (status != ResumeStatus.REJECTED_SCREEN &&
                status != ResumeStatus.INTERVIEW_REJECTED &&
                status != ResumeStatus.REJECTED &&
                status != ResumeStatus.CANCELLED) {
                log.warn("ResumeCheck: 候选人已有该职位的活跃简历, resumeId: {}, status: {}",
                        resume.getResumeId(), status);
                throw new RuntimeException("已投递过该职位，请勿重复投递");
            }
        }
    }

    public void validateCandidateApplicationHistory(String candidateId, String targetPositionId) {
        log.debug("ResumeCheck: 检查候选人投递历史, candidateId: {}, targetPositionId: {}",
                candidateId, targetPositionId);

        List<Resume> candidateResumes = resumeRepository.findByCandidateId(candidateId);

        for (Resume resume : candidateResumes) {
            if (resume.getPositionId().equals(targetPositionId)) {
                continue;
            }

            ResumeStatus status = resume.getResumeStatus();
            if (isActiveResumeStatus(status)) {
                log.debug("ResumeCheck: 候选人在其他职位有活跃简历: {}, 状态: {}",
                        resume.getPositionId(), status);
            }
        }
    }

    private boolean isActiveResumeStatus(ResumeStatus status) {
        return status == ResumeStatus.PENDING_SCREEN ||
               status == ResumeStatus.SCREENED ||
               status == ResumeStatus.IN_INTERVIEW ||
               status == ResumeStatus.INTERVIEW_PASSED ||
               status == ResumeStatus.IN_HIRE;
    }

    public void validatePositionAvailability(String positionId) {
        log.debug("ResumeCheck: 验证职位可用性, positionId: {}", positionId);

        Position position = positionService.getPosition(positionId);

        if (position.getPositionCount() == null || position.getPositionCount() <= 0) {
            throw new RuntimeException("该职位招聘人数配置不正确");
        }

        Integer currentResumeCount = position.getResumeCount();
        Integer positionCount = position.getPositionCount();

        if (currentResumeCount != null && currentResumeCount >= positionCount * 5) {
            log.warn("ResumeCheck: 职位简历数量过多, positionId: {}, 当前: {}, 限制: {}",
                    positionId, currentResumeCount, positionCount * 5);
        }
    }

    public ResumeCheckResult performFullCheck(String positionId, String candidateId) {
        log.info("ResumeCheck: 执行完整简历检查, positionId: {}, candidateId: {}", positionId, candidateId);

        ResumeCheckResult result = ResumeCheckResult.builder()
                .positionId(positionId)
                .candidateId(candidateId)
                .passed(true)
                .build();

        try {
            validatePositionStatus(positionId);
            result.setPositionStatusValid(true);
        } catch (RuntimeException e) {
            result.setPositionStatusValid(false);
            result.setErrorPositionStatus(e.getMessage());
            result.setPassed(false);
        }

        try {
            validateDuplicateSubmission(positionId, candidateId);
            result.setDuplicateCheckPassed(true);
        } catch (RuntimeException e) {
            result.setDuplicateCheckPassed(false);
            result.setErrorDuplicate(e.getMessage());
            result.setPassed(false);
        }

        try {
            validatePositionAvailability(positionId);
            result.setAvailabilityCheckPassed(true);
        } catch (RuntimeException e) {
            result.setAvailabilityCheckPassed(false);
            result.setErrorAvailability(e.getMessage());
            result.setPassed(false);
        }

        try {
            validateCandidateApplicationHistory(candidateId, positionId);
            result.setHistoryCheckPassed(true);
        } catch (RuntimeException e) {
            result.setHistoryCheckPassed(false);
            result.setErrorHistory(e.getMessage());
            result.setPassed(false);
        }

        if (result.isPassed()) {
            log.info("ResumeCheck: 完整检查通过");
        } else {
            log.warn("ResumeCheck: 完整检查失败: {}", result.getErrorMessage());
        }

        return result;
    }

    public List<Resume> getCandidateActiveResumes(String candidateId) {
        List<Resume> allResumes = resumeRepository.findByCandidateId(candidateId);
        return allResumes.stream()
                .filter(r -> isActiveResumeStatus(r.getResumeStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    public long countCandidateActiveResumes(String candidateId) {
        return getCandidateActiveResumes(candidateId).size();
    }

    public boolean canApplyToOtherPositions(String candidateId, String excludePositionId) {
        List<Resume> activeResumes = getCandidateActiveResumes(candidateId);
        long otherActiveResumes = activeResumes.stream()
                .filter(r -> !r.getPositionId().equals(excludePositionId))
                .count();

        return otherActiveResumes < 3;
    }

    public Optional<Resume> findExistingResume(String positionId, String candidateId) {
        List<Resume> resumes = resumeRepository.findByPositionIdAndCandidateIdList(positionId, candidateId);
        if (resumes.isEmpty()) {
            return Optional.empty();
        }
        return resumes.stream()
                .filter(r -> isActiveResumeStatus(r.getResumeStatus()))
                .findFirst()
                .or(() -> resumes.stream().findFirst());
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ResumeCheckResult {
        private String positionId;
        private String candidateId;

        @lombok.Builder.Default
        private boolean passed = false;

        @lombok.Builder.Default
        private boolean positionStatusValid = false;

        @lombok.Builder.Default
        private boolean duplicateCheckPassed = false;

        @lombok.Builder.Default
        private boolean availabilityCheckPassed = false;

        @lombok.Builder.Default
        private boolean historyCheckPassed = false;

        private String errorPositionStatus;
        private String errorDuplicate;
        private String errorAvailability;
        private String errorHistory;

        public String getErrorMessage() {
            if (errorPositionStatus != null) return errorPositionStatus;
            if (errorDuplicate != null) return errorDuplicate;
            if (errorAvailability != null) return errorAvailability;
            if (errorHistory != null) return errorHistory;
            return null;
        }
    }
}
