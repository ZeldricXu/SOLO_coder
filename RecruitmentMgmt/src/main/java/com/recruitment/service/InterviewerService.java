package com.recruitment.service;

import com.recruitment.common.enums.InterviewerStatus;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.model.Interviewer;
import com.recruitment.repository.InterviewerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewerService {
    private final InterviewerRepository interviewerRepository;

    @Transactional
    public Interviewer createInterviewer(String name, String department, InterviewType type) {
        String interviewerId = IdGenerator.generateInterviewerId();
        Interviewer interviewer = Interviewer.builder()
                .interviewerId(interviewerId)
                .interviewerName(name)
                .interviewerDepartment(department)
                .interviewerType(type)
                .interviewerStatus(InterviewerStatus.AVAILABLE)
                .interviewerCount(0)
                .completedCount(0)
                .build();
        Interviewer saved = interviewerRepository.save(interviewer);
        log.info("Interviewer: 创建面试官成功, interviewerId: {}", interviewerId);
        return saved;
    }

    @Transactional
    public Interviewer updateInterviewerStatus(String interviewerId, InterviewerStatus newStatus) {
        Interviewer interviewer = getInterviewer(interviewerId);
        interviewer.setInterviewerStatus(newStatus);
        Interviewer saved = interviewerRepository.save(interviewer);
        log.info("Interviewer: 更新面试官状态, interviewerId: {}, status: {}", interviewerId, newStatus);
        return saved;
    }

    @Transactional
    public void incrementInterviewCount(String interviewerId) {
        Interviewer interviewer = getInterviewer(interviewerId);
        interviewer.setInterviewerCount(interviewer.getInterviewerCount() + 1);
        interviewerRepository.save(interviewer);
        log.debug("Interviewer: 面试官面试计数+1, interviewerId: {}", interviewerId);
    }

    @Transactional
    public void incrementCompletedCount(String interviewerId) {
        Interviewer interviewer = getInterviewer(interviewerId);
        interviewer.setCompletedCount(interviewer.getCompletedCount() + 1);
        interviewerRepository.save(interviewer);
        log.debug("Interviewer: 面试官完成计数+1, interviewerId: {}", interviewerId);
    }

    public Interviewer getInterviewer(String interviewerId) {
        return interviewerRepository.findByInterviewerId(interviewerId)
                .orElseThrow(() -> new RuntimeException("面试官不存在: " + interviewerId));
    }

    public List<Interviewer> getAllInterviewers() {
        return interviewerRepository.findAll();
    }

    public List<Interviewer> getAvailableInterviewers() {
        return interviewerRepository.findByInterviewerStatus(InterviewerStatus.AVAILABLE);
    }

    public List<Interviewer> getInterviewersByType(InterviewType type) {
        return interviewerRepository.findByInterviewerType(type);
    }

    public List<Interviewer> getAvailableInterviewersByType(InterviewType type) {
        return interviewerRepository.findByInterviewerStatusAndInterviewerType(InterviewerStatus.AVAILABLE, type);
    }

    public Optional<Interviewer> findAvailableInterviewer(InterviewType type) {
        List<Interviewer> available = getAvailableInterviewersByType(type);
        if (available.isEmpty()) {
            available = getAvailableInterviewers();
        }
        if (available.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(available.get(0));
    }

    public boolean isInterviewerAvailable(String interviewerId) {
        try {
            Interviewer interviewer = getInterviewer(interviewerId);
            return interviewer.getInterviewerStatus() == InterviewerStatus.AVAILABLE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void validateInterviewerForAssignment(String interviewerId) {
        Interviewer interviewer = getInterviewer(interviewerId);
        if (interviewer.getInterviewerStatus() == InterviewerStatus.UNAVAILABLE) {
            throw new RuntimeException("面试官不可用");
        }
        if (interviewer.getInterviewerStatus() == InterviewerStatus.BUSY) {
            throw new RuntimeException("面试官当前忙碌");
        }
    }
}
