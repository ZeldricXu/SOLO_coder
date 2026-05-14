package com.recruitment.history;

import com.recruitment.common.enums.HistoryType;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.model.History;
import com.recruitment.repository.HistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {
    private final HistoryRepository historyRepository;

    @Transactional
    public void recordResumeSubmit(String resumeId, String positionId, String candidateId) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.RESUME_SUBMIT)
                .relatedId(resumeId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .action("简历投递")
                .oldStatus(null)
                .newStatus("PENDING_SCREEN")
                .description("候选人投递简历")
                .build();
        historyRepository.save(history);
        log.debug("History: 记录简历投递, resumeId: {}", resumeId);
    }

    @Transactional
    public void recordResumeScreen(String resumeId, String positionId, String candidateId,
                                   String oldStatus, String newStatus, String description) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.RESUME_SCREEN)
                .relatedId(resumeId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .action("简历筛选")
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .description(description)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录简历筛选, resumeId: {}, status: {}", resumeId, newStatus);
    }

    @Transactional
    public void recordInterviewSchedule(String interviewId, String resumeId, String positionId,
                                        String candidateId, String interviewerId) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.INTERVIEW_SCHEDULE)
                .relatedId(interviewId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .interviewId(interviewId)
                .action("面试安排")
                .oldStatus("SCREENED")
                .newStatus("SCHEDULED")
                .description("安排面试，面试官ID: " + interviewerId)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录面试安排, interviewId: {}", interviewId);
    }

    @Transactional
    public void recordInterviewExecute(String interviewId, String resumeId, String positionId,
                                       String candidateId, String oldStatus, String newStatus,
                                       String description) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.INTERVIEW_EXECUTE)
                .relatedId(interviewId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .interviewId(interviewId)
                .action("面试执行")
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .description(description)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录面试执行, interviewId: {}, status: {}", interviewId, newStatus);
    }

    @Transactional
    public void recordHireProcess(String hireId, String resumeId, String positionId,
                                  String candidateId, String oldStatus, String newStatus) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.HIRE_PROCESS)
                .relatedId(hireId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .hireId(hireId)
                .action("录用处理")
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .description("进入录用流程")
                .build();
        historyRepository.save(history);
        log.debug("History: 记录录用处理, hireId: {}", hireId);
    }

    @Transactional
    public void recordHireApprove(String hireId, String resumeId, String positionId,
                                  String candidateId, String oldStatus, String newStatus,
                                  String description) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.HIRE_APPROVE)
                .relatedId(hireId)
                .positionId(positionId)
                .resumeId(resumeId)
                .candidateId(candidateId)
                .hireId(hireId)
                .action("录用审批")
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .description(description)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录录用审批, hireId: {}, status: {}", hireId, newStatus);
    }

    @Transactional
    public void recordPositionCreate(String positionId, String description) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.POSITION_CREATE)
                .relatedId(positionId)
                .positionId(positionId)
                .action("职位创建")
                .oldStatus(null)
                .newStatus("DRAFT")
                .description(description)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录职位创建, positionId: {}", positionId);
    }

    @Transactional
    public void recordPositionUpdate(String positionId, String oldStatus, String newStatus, String description) {
        History history = History.builder()
                .historyId(IdGenerator.generateHistoryId())
                .historyType(HistoryType.POSITION_UPDATE)
                .relatedId(positionId)
                .positionId(positionId)
                .action("职位更新")
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .description(description)
                .build();
        historyRepository.save(history);
        log.debug("History: 记录职位更新, positionId: {}, status: {}", positionId, newStatus);
    }

    public List<History> getHistoryByResume(String resumeId) {
        return historyRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    public List<History> getHistoryByCandidate(String candidateId) {
        return historyRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId);
    }

    public List<History> getHistoryByPosition(String positionId) {
        return historyRepository.findByPositionIdOrderByCreatedAtDesc(positionId);
    }

    public List<History> getHistoryByType(HistoryType type) {
        return historyRepository.findByHistoryType(type);
    }
}
