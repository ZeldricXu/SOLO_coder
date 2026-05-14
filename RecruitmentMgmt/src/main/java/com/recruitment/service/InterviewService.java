package com.recruitment.service;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.common.enums.InterviewStatus;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.dto.InterviewArrangeRequest;
import com.recruitment.dto.InterviewArrangeResponse;
import com.recruitment.dto.InterviewExecuteRequest;
import com.recruitment.history.HistoryService;
import com.recruitment.model.Interview;
import com.recruitment.model.Interviewer;
import com.recruitment.model.Position;
import com.recruitment.model.Resume;
import com.recruitment.repository.InterviewRepository;
import com.recruitment.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {
    private final InterviewRepository interviewRepository;
    private final ResumeService resumeService;
    private final PositionService positionService;
    private final CandidateService candidateService;
    private final InterviewerService interviewerService;
    private final HireService hireService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final WorkflowService workflowService;
    private final InterviewReminderService interviewReminderService;
    private final HireApprovalWorkerService hireApprovalWorkerService;
    private final PositionTypeConfigService positionTypeConfigService;

    @Transactional
    public InterviewArrangeResponse arrangeInterview(InterviewArrangeRequest request) {
        log.info("Interview: 开始安排面试, resumeId: {}", request.getResumeId());

        Resume resume = resumeService.getResume(request.getResumeId());

        if (resume.getResumeStatus() != ResumeStatus.SCREENED) {
            throw new RuntimeException("简历未通过筛选，无法安排面试");
        }

        Position position = positionService.getPosition(resume.getPositionId());

        interviewerService.validateInterviewerForAssignment(request.getInterviewerId());
        Interviewer interviewer = interviewerService.getInterviewer(request.getInterviewerId());

        InterviewType interviewType = parseInterviewType(request.getInterviewType());
        if (interviewType == null) {
            List<InterviewType> stages = positionTypeConfigService.getInterviewStagesForType(position.getPositionType());
            if (!stages.isEmpty()) {
                interviewType = stages.get(0);
            } else {
                interviewType = InterviewType.TECHNICAL;
            }
        }

        String interviewId = IdGenerator.generateInterviewId();
        Interview interview = Interview.builder()
                .interviewId(interviewId)
                .resumeId(request.getResumeId())
                .interviewType(interviewType)
                .interviewerId(request.getInterviewerId())
                .interviewTime(request.getInterviewTime())
                .interviewStatus(InterviewStatus.SCHEDULED)
                .build();

        interviewRepository.save(interview);

        resumeService.updateResumeStatus(request.getResumeId(), ResumeStatus.IN_INTERVIEW);

        candidateService.updateCandidateStatus(resume.getCandidateId(), CandidateStatus.INTERVIEWING);

        interviewerService.incrementInterviewCount(request.getInterviewerId());

        historyService.recordInterviewSchedule(
                interviewId,
                request.getResumeId(),
                position.getPositionId(),
                resume.getCandidateId(),
                request.getInterviewerId()
        );

        triggerInterviewReminder(interview);

        log.info("Interview: 面试安排成功, interviewId: {}, interviewer: {}", interviewId, interviewer.getInterviewerName());

        return InterviewArrangeResponse.builder()
                .interviewId(interviewId)
                .status(InterviewStatus.SCHEDULED.name())
                .interviewerName(interviewer.getInterviewerName())
                .reminderScheduled(true)
                .build();
    }

    @Async
    public void triggerInterviewReminder(Interview interview) {
        log.info("Interview: 安排后触发面试提醒, interviewId: {}", interview.getInterviewId());
        try {
            interviewReminderService.sendInterviewReminder(interview);
            log.info("Interview: 面试提醒已触发, interviewId: {}", interview.getInterviewId());
        } catch (Exception e) {
            log.error("Interview: 发送面试提醒失败, interviewId: {}, 错误: {}",
                    interview.getInterviewId(), e.getMessage());
        }
    }

    @Transactional
    public Interview executeInterview(InterviewExecuteRequest request) {
        log.info("Interview: 开始执行面试, interviewId: {}", request.getInterviewId());

        Interview interview = getInterview(request.getInterviewId());

        if (interview.getInterviewStatus() != InterviewStatus.SCHEDULED &&
                interview.getInterviewStatus() != InterviewStatus.IN_PROGRESS) {
            if (interview.getInterviewStatus() == InterviewStatus.PASSED ||
                    interview.getInterviewStatus() == InterviewStatus.REJECTED) {
                throw new RuntimeException("面试已完成");
            }
            throw new RuntimeException("面试状态不允许执行: " + interview.getInterviewStatus());
        }

        Resume resume = resumeService.getResume(interview.getResumeId());
        Position position = positionService.getPosition(resume.getPositionId());

        String oldStatus = interview.getInterviewStatus().name();
        boolean passed = request.getPassed() != null ? request.getPassed() :
                (request.getInterviewScore() != null && request.getInterviewScore() >= 60);

        String description;
        if (passed) {
            interview.setInterviewStatus(InterviewStatus.PASSED);
            interview.setInterviewResult(request.getInterviewResult() != null ?
                    request.getInterviewResult() : "面试通过");
            interview.setInterviewScore(request.getInterviewScore());
            interview.setTechEvaluation(request.getTechEvaluation());
            interview.setOverallEvaluation(request.getOverallEvaluation());
            description = "面试通过";

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.INTERVIEW_PASSED);
            candidateService.updateCandidateStatus(resume.getCandidateId(), CandidateStatus.IN_HIRE);

            initiateAsyncHireProcess(resume.getResumeId(), resume.getCandidateId(), position.getPositionId());
        } else {
            interview.setInterviewStatus(InterviewStatus.REJECTED);
            interview.setInterviewResult(request.getInterviewResult() != null ?
                    request.getInterviewResult() : "面试未通过");
            interview.setInterviewScore(request.getInterviewScore());
            interview.setTechEvaluation(request.getTechEvaluation());
            interview.setOverallEvaluation(request.getOverallEvaluation());
            interview.setRejectReason(request.getRejectReason() != null ?
                    request.getRejectReason() : "面试未通过");
            description = "面试淘汰: " + interview.getRejectReason();

            resumeService.updateResumeStatus(resume.getResumeId(), ResumeStatus.INTERVIEW_REJECTED);
            candidateService.updateCandidateStatus(resume.getCandidateId(), CandidateStatus.REJECTED);
            analysisService.incrementRejectCount();
        }

        Interview saved = interviewRepository.save(interview);

        interviewerService.incrementCompletedCount(interview.getInterviewerId());
        analysisService.incrementInterviewCount();

        historyService.recordInterviewExecute(
                interview.getInterviewId(),
                interview.getResumeId(),
                position.getPositionId(),
                resume.getCandidateId(),
                oldStatus,
                interview.getInterviewStatus().name(),
                description
        );

        log.info("Interview: 面试执行完成, interviewId: {}, passed: {}", interview.getInterviewId(), passed);

        return saved;
    }

    public void initiateAsyncHireProcess(String resumeId, String candidateId, String positionId) {
        log.info("Interview: 面试通过，发起异步录用流程, resumeId: {}", resumeId);

        hireService.initiateHireProcess(resumeId, candidateId, positionId);

        log.info("Interview: 异步录用流程已启动，立即返回");
    }

    public Interview getInterview(String interviewId) {
        return interviewRepository.findByInterviewId(interviewId)
                .orElseThrow(() -> new RuntimeException("面试不存在: " + interviewId));
    }

    public List<Interview> getAllInterviews() {
        return interviewRepository.findAll();
    }

    public List<Interview> getInterviewsByResume(String resumeId) {
        return interviewRepository.findByResumeId(resumeId);
    }

    public List<Interview> getInterviewsByInterviewer(String interviewerId) {
        return interviewRepository.findByInterviewerId(interviewerId);
    }

    public List<Interview> getInterviewsByStatus(InterviewStatus status) {
        return interviewRepository.findByInterviewStatus(status);
    }

    public List<Interview> getScheduledInterviews() {
        return interviewRepository.findByInterviewStatus(InterviewStatus.SCHEDULED);
    }

    private InterviewType parseInterviewType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        try {
            return InterviewType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
