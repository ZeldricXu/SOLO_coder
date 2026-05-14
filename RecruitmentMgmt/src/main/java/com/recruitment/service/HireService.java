package com.recruitment.service;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.common.enums.HireStatus;
import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.dto.HireApproveRequest;
import com.recruitment.dto.HireApproveResponse;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Candidate;
import com.recruitment.model.Hire;
import com.recruitment.model.Resume;
import com.recruitment.repository.HireRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HireService {
    private final HireRepository hireRepository;
    private final ResumeService resumeService;
    private final CandidateService candidateService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;

    @Transactional
    public Hire initiateHireProcess(String resumeId, String candidateId, String positionId) {
        log.info("Hire: 初始化录用流程, resumeId: {}", resumeId);

        boolean exists = hireRepository.existsByResumeId(resumeId);
        if (exists) {
            return hireRepository.findByResumeId(resumeId)
                    .orElseThrow(() -> new RuntimeException("录用记录查询失败"));
        }

        String hireId = IdGenerator.generateHireId();
        Hire hire = Hire.builder()
                .hireId(hireId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .hireStatus(HireStatus.PENDING_APPROVAL)
                .build();

        Hire saved = hireRepository.save(hire);

        resumeService.updateResumeStatus(resumeId, ResumeStatus.IN_HIRE);

        historyService.recordHireProcess(
                hireId,
                resumeId,
                positionId,
                candidateId,
                "INTERVIEW_PASSED",
                "PENDING_APPROVAL"
        );

        log.info("Hire: 录用流程初始化成功, hireId: {}", hireId);

        return saved;
    }

    @Transactional
    public HireApproveResponse approveHire(HireApproveRequest request) {
        log.info("Hire: 处理录用审批, resumeId: {}", request.getResumeId());

        Hire hire = hireRepository.findByResumeId(request.getResumeId())
                .orElseThrow(() -> new RuntimeException("录用记录不存在: " + request.getResumeId()));

        if (hire.getHireStatus() != HireStatus.PENDING_APPROVAL) {
            throw new RuntimeException("录用状态不允许审批: " + hire.getHireStatus());
        }

        Resume resume = resumeService.getResume(hire.getResumeId());
        Candidate candidate = candidateService.getCandidate(hire.getCandidateId());

        String oldStatus = hire.getHireStatus().name();
        boolean approved = request.getApproved() != null ? request.getApproved() : true;

        String description;
        if (approved) {
            hire.setHireStatus(HireStatus.APPROVED);
            hire.setHireSalary(request.getHireSalary());
            hire.setHireDate(request.getHireDate());
            hire.setApprovedAt(Instant.now());
            description = "录用审批通过";

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.HIRED);
            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.HIRED);
            analysisService.incrementHireCount();
        } else {
            hire.setHireStatus(HireStatus.REJECTED);
            hire.setRejectReason(request.getHireSalary() != null ?
                    "审批未通过" : "审批未通过");
            description = "录用审批未通过";

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.REJECTED);
            candidateService.updateCandidateStatus(candidate.getCandidateId(), CandidateStatus.REJECTED);
            analysisService.incrementRejectCount();
        }

        Hire saved = hireRepository.save(hire);

        historyService.recordHireApprove(
                hire.getHireId(),
                hire.getResumeId(),
                resume.getPositionId(),
                hire.getCandidateId(),
                oldStatus,
                hire.getHireStatus().name(),
                description
        );

        log.info("Hire: 录用审批完成, hireId: {}, approved: {}", hire.getHireId(), approved);

        return HireApproveResponse.builder()
                .hireId(hire.getHireId())
                .status(hire.getHireStatus().name())
                .candidateName(candidate.getCandidateName())
                .build();
    }

    @Transactional
    public Hire confirmHire(String hireId) {
        log.info("Hire: 确认录用, hireId: {}", hireId);

        Hire hire = getHire(hireId);

        if (hire.getHireStatus() != HireStatus.APPROVED) {
            throw new RuntimeException("录用状态不允许确认: " + hire.getHireStatus());
        }

        hire.setHireStatus(HireStatus.CONFIRMED);
        return hireRepository.save(hire);
    }

    public Hire getHire(String hireId) {
        return hireRepository.findByHireId(hireId)
                .orElseThrow(() -> new RuntimeException("录用记录不存在: " + hireId));
    }

    public Hire getHireByResume(String resumeId) {
        return hireRepository.findByResumeId(resumeId)
                .orElseThrow(() -> new RuntimeException("该简历没有录用记录: " + resumeId));
    }

    public List<Hire> getAllHires() {
        return hireRepository.findAll();
    }

    public List<Hire> getHiresByStatus(HireStatus status) {
        return hireRepository.findByHireStatus(status);
    }

    public List<Hire> getPendingApprovalHires() {
        return hireRepository.findByHireStatus(HireStatus.PENDING_APPROVAL);
    }

    public List<Hire> getHiresByCandidate(String candidateId) {
        return hireRepository.findByCandidateId(candidateId);
    }
}
