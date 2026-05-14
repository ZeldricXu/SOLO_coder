package com.recruitment.builder;

import com.recruitment.common.enums.*;
import com.recruitment.dto.*;
import com.recruitment.model.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static Position createTestPosition() {
        return Position.builder()
                .positionId(generateId("position"))
                .positionName("Java后端开发工程师")
                .positionType(PositionType.TECHNICAL)
                .positionDepartment("研发部")
                .positionStatus(PositionStatus.RECRUITING)
                .positionCount(5)
                .resumeCount(0)
                .positionSalary("20K-35K")
                .positionRequirement("3年以上Java开发经验，熟悉Spring Boot")
                .createdAt(Instant.now())
                .build();
    }

    public static Position createTestPosition(PositionStatus status) {
        Position position = createTestPosition();
        position.setPositionStatus(status);
        return position;
    }

    public static Position createTestPosition(String name, PositionType type, PositionStatus status) {
        Position position = createTestPosition();
        position.setPositionName(name);
        position.setPositionType(type);
        position.setPositionStatus(status);
        return position;
    }

    public static Position createDraftPosition() {
        return createTestPosition(PositionStatus.DRAFT);
    }

    public static Position createClosedPosition() {
        return createTestPosition(PositionStatus.CLOSED);
    }

    public static Position createSuspendedPosition() {
        return createTestPosition(PositionStatus.SUSPENDED);
    }

    public static Position createFullPosition() {
        Position position = createTestPosition();
        position.setResumeCount(position.getPositionCount());
        return position;
    }

    public static Candidate createTestCandidate() {
        return Candidate.builder()
                .candidateId(generateId("candidate"))
                .candidateName("张三")
                .candidatePhone("13800138001")
                .candidateEmail("zhangsan@example.com")
                .candidateEducation("本科")
                .candidateExperience("5年")
                .candidateStatus(CandidateStatus.REGISTERED)
                .registeredAt(Instant.now())
                .build();
    }

    public static Candidate createTestCandidate(String name, String phone) {
        Candidate candidate = createTestCandidate();
        candidate.setCandidateName(name);
        candidate.setCandidatePhone(phone);
        return candidate;
    }

    public static Candidate createTestCandidate(CandidateStatus status) {
        Candidate candidate = createTestCandidate();
        candidate.setCandidateStatus(status);
        return candidate;
    }

    public static Interviewer createTestInterviewer() {
        return Interviewer.builder()
                .interviewerId(generateId("interviewer"))
                .interviewerName("李经理")
                .interviewerDepartment("研发部")
                .interviewerType(InterviewType.TECHNICAL)
                .interviewerStatus(InterviewerStatus.AVAILABLE)
                .interviewerCount(0)
                .completedCount(0)
                .createdAt(Instant.now())
                .build();
    }

    public static Interviewer createTestInterviewer(InterviewerStatus status) {
        Interviewer interviewer = createTestInterviewer();
        interviewer.setInterviewerStatus(status);
        return interviewer;
    }

    public static Interviewer createTestInterviewer(String name, InterviewType type) {
        Interviewer interviewer = createTestInterviewer();
        interviewer.setInterviewerName(name);
        interviewer.setInterviewerType(type);
        return interviewer;
    }

    public static Resume createTestResume() {
        return Resume.builder()
                .resumeId(generateId("resume"))
                .positionId(generateId("position"))
                .candidateId(generateId("candidate"))
                .resumeStatus(ResumeStatus.PENDING_SCREEN)
                .resumeSource(ResumeSource.PLATFORM)
                .resumeTime(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    public static Resume createTestResume(String positionId, String candidateId) {
        Resume resume = createTestResume();
        resume.setPositionId(positionId);
        resume.setCandidateId(candidateId);
        return resume;
    }

    public static Resume createTestResume(ResumeStatus status) {
        Resume resume = createTestResume();
        resume.setResumeStatus(status);
        return resume;
    }

    public static Resume createTestResume(String positionId, String candidateId, ResumeStatus status) {
        Resume resume = createTestResume(positionId, candidateId);
        resume.setResumeStatus(status);
        return resume;
    }

    public static Resume createScreenedResume(String positionId, String candidateId) {
        Resume resume = createTestResume(positionId, candidateId, ResumeStatus.SCREENED);
        resume.setScreenedAt(Instant.now());
        resume.setScreenResult("通过筛选");
        return resume;
    }

    public static Resume createInterviewingResume(String positionId, String candidateId) {
        return createTestResume(positionId, candidateId, ResumeStatus.IN_INTERVIEW);
    }

    public static Interview createTestInterview() {
        return Interview.builder()
                .interviewId(generateId("interview"))
                .resumeId(generateId("resume"))
                .interviewType(InterviewType.TECHNICAL)
                .interviewerId(generateId("interviewer"))
                .interviewTime(Instant.now().plusSeconds(86400))
                .interviewStatus(InterviewStatus.SCHEDULED)
                .createdAt(Instant.now())
                .build();
    }

    public static Interview createTestInterview(String resumeId, String interviewerId) {
        Interview interview = createTestInterview();
        interview.setResumeId(resumeId);
        interview.setInterviewerId(interviewerId);
        return interview;
    }

    public static Interview createTestInterview(InterviewStatus status) {
        Interview interview = createTestInterview();
        interview.setInterviewStatus(status);
        return interview;
    }

    public static Interview createUrgentInterview() {
        Interview interview = createTestInterview();
        interview.setInterviewTime(Instant.now().plusSeconds(3600));
        return interview;
    }

    public static Interview createNormalInterview() {
        Interview interview = createTestInterview();
        interview.setInterviewTime(Instant.now().plusSeconds(86400 * 3));
        return interview;
    }

    public static Hire createTestHire() {
        return Hire.builder()
                .hireId(generateId("hire"))
                .resumeId(generateId("resume"))
                .candidateId(generateId("candidate"))
                .hireStatus(HireStatus.PENDING_APPROVAL)
                .createdAt(Instant.now())
                .build();
    }

    public static Hire createTestHire(String resumeId, String candidateId) {
        Hire hire = createTestHire();
        hire.setResumeId(resumeId);
        hire.setCandidateId(candidateId);
        return hire;
    }

    public static Hire createTestHire(HireStatus status) {
        Hire hire = createTestHire();
        hire.setHireStatus(status);
        return hire;
    }

    public static ResumeSubmitRequest createResumeSubmitRequest() {
        return ResumeSubmitRequest.builder()
                .positionId(generateId("position"))
                .candidateName("李四")
                .candidatePhone("13900139002")
                .candidateEmail("lisi@example.com")
                .candidateEducation("硕士")
                .candidateExperience("3年")
                .resumeSource("PLATFORM")
                .build();
    }

    public static ResumeSubmitRequest createResumeSubmitRequest(String positionId) {
        ResumeSubmitRequest request = createResumeSubmitRequest();
        request.setPositionId(positionId);
        return request;
    }

    public static ResumeSubmitRequest createResumeSubmitRequest(String positionId, String phone) {
        ResumeSubmitRequest request = createResumeSubmitRequest(positionId);
        request.setCandidatePhone(phone);
        return request;
    }

    public static ResumeScreenRequest createResumeScreenRequest(String resumeId, boolean passed) {
        return ResumeScreenRequest.builder()
                .resumeId(resumeId)
                .passed(passed)
                .screenResult(passed ? "符合要求，进入面试" : "不符合招聘要求")
                .rejectReason(passed ? null : "经验不足")
                .build();
    }

    public static InterviewArrangeRequest createInterviewArrangeRequest(String resumeId, String interviewerId) {
        return InterviewArrangeRequest.builder()
                .resumeId(resumeId)
                .interviewerId(interviewerId)
                .interviewTime(Instant.now().plusSeconds(86400))
                .interviewType("TECHNICAL")
                .build();
    }

    public static InterviewExecuteRequest createInterviewExecuteRequest(String interviewId, boolean passed) {
        return InterviewExecuteRequest.builder()
                .interviewId(interviewId)
                .passed(passed)
                .interviewScore(passed ? 85 : 50)
                .interviewResult(passed ? "表现优秀" : "表现不佳")
                .techEvaluation(passed ? "技术能力强" : "技术能力不足")
                .overallEvaluation(passed ? "综合评价优秀" : "综合评价不合格")
                .rejectReason(passed ? null : "技术基础薄弱")
                .build();
    }

    public static HireApproveRequest createHireApproveRequest(String resumeId, boolean approved) {
        return HireApproveRequest.builder()
                .resumeId(resumeId)
                .hireSalary("25K")
                .hireDate(LocalDate.now().plusDays(30))
                .approved(approved)
                .build();
    }

    public static Statistics createTestStatistics() {
        return Statistics.builder()
                .statId(generateId("stat"))
                .statMonth("2026-05")
                .positionCount(10)
                .resumeCount(100)
                .screenedCount(50)
                .interviewCount(30)
                .hireCount(10)
                .rejectCount(40)
                .createdAt(Instant.now())
                .build();
    }

    public static Workflow createTestWorkflow() {
        List<InterviewType> stages = new ArrayList<>();
        stages.add(InterviewType.TECHNICAL);
        stages.add(InterviewType.MANAGERIAL);
        stages.add(InterviewType.HR);

        return Workflow.builder()
                .workflowId(generateId("workflow"))
                .workflowName("技术岗招聘流程")
                .positionType("TECHNICAL")
                .isDefault(true)
                .stages(stages)
                .screenRules("学历本科以上，3年以上工作经验")
                .createdAt(Instant.now())
                .build();
    }

    public static History createTestHistory() {
        return History.builder()
                .historyId(generateId("history"))
                .historyType(HistoryType.RESUME_SUBMIT)
                .relatedId(generateId("resume"))
                .positionId(generateId("position"))
                .resumeId(generateId("resume"))
                .candidateId(generateId("candidate"))
                .action("简历投递")
                .oldStatus(null)
                .newStatus("PENDING_SCREEN")
                .description("候选人投递简历")
                .createdAt(Instant.now())
                .build();
    }

    public static List<Position> createMultiplePositions(int count) {
        List<Position> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Position position = createTestPosition();
            position.setPositionName("测试职位_" + (i + 1));
            positions.add(position);
        }
        return positions;
    }

    public static List<Candidate> createMultipleCandidates(int count) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Candidate candidate = createTestCandidate();
            candidate.setCandidateName("候选人_" + (i + 1));
            candidate.setCandidatePhone("13800" + String.format("%06d", 100000 + i));
            candidates.add(candidate);
        }
        return candidates;
    }

    public static List<Interviewer> createMultipleInterviewers(int count) {
        List<Interviewer> interviewers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Interviewer interviewer = createTestInterviewer();
            interviewer.setInterviewerName("面试官_" + (i + 1));
            interviewers.add(interviewer);
        }
        return interviewers;
    }
}
