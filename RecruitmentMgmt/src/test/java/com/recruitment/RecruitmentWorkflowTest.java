package com.recruitment;

import com.recruitment.common.enums.*;
import com.recruitment.dto.*;
import com.recruitment.model.*;
import com.recruitment.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RecruitmentWorkflowTest {

    @Autowired
    private PositionService positionService;

    @Autowired
    private CandidateService candidateService;

    @Autowired
    private InterviewerService interviewerService;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private HireService hireService;

    private Position testPosition;
    private Interviewer testInterviewer;

    @BeforeEach
    void setUp() {
        testPosition = positionService.createPosition(
                "Java后端开发工程师",
                PositionType.TECHNICAL,
                "研发部",
                5,
                "20K-35K",
                "3年以上Java开发经验"
        );
        positionService.publishPosition(testPosition.getPositionId());

        testInterviewer = interviewerService.createInterviewer(
                "张三",
                "研发部",
                InterviewType.TECHNICAL
        );
    }

    @Test
    void testCompleteRecruitmentWorkflow() {
        ResumeSubmitRequest submitRequest = ResumeSubmitRequest.builder()
                .positionId(testPosition.getPositionId())
                .candidateName("李四")
                .candidatePhone("13800138001")
                .candidateEmail("lisi@example.com")
                .candidateEducation("本科")
                .candidateExperience("5年")
                .build();

        ResumeSubmitResponse submitResponse = resumeService.submitResume(submitRequest);

        assertNotNull(submitResponse);
        assertNotNull(submitResponse.getResumeId());
        assertEquals(ResumeStatus.PENDING_SCREEN.name(), submitResponse.getStatus());

        Resume resume = resumeService.getResume(submitResponse.getResumeId());
        assertEquals(ResumeStatus.PENDING_SCREEN, resume.getResumeStatus());

        ResumeScreenRequest screenRequest = ResumeScreenRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .passed(true)
                .screenResult("符合要求，进入面试")
                .build();

        Resume screenedResume = resumeService.screenResume(screenRequest);
        assertEquals(ResumeStatus.SCREENED, screenedResume.getResumeStatus());

        Candidate candidate = candidateService.getCandidate(submitResponse.getCandidateId());
        assertEquals(CandidateStatus.SCREENED, candidate.getCandidateStatus());

        InterviewArrangeRequest arrangeRequest = InterviewArrangeRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .interviewerId(testInterviewer.getInterviewerId())
                .interviewTime(Instant.now().plusSeconds(86400))
                .interviewType("TECHNICAL")
                .build();

        InterviewArrangeResponse arrangeResponse = interviewService.arrangeInterview(arrangeRequest);
        assertNotNull(arrangeResponse);
        assertNotNull(arrangeResponse.getInterviewId());
        assertEquals(InterviewStatus.SCHEDULED.name(), arrangeResponse.getStatus());

        Resume afterInterviewResume = resumeService.getResume(submitResponse.getResumeId());
        assertEquals(ResumeStatus.IN_INTERVIEW, afterInterviewResume.getResumeStatus());

        InterviewExecuteRequest executeRequest = InterviewExecuteRequest.builder()
                .interviewId(arrangeResponse.getInterviewId())
                .passed(true)
                .interviewScore(85)
                .interviewResult("表现优秀")
                .techEvaluation("技术能力强")
                .overallEvaluation("综合评价优秀")
                .build();

        Interview executedInterview = interviewService.executeInterview(executeRequest);
        assertEquals(InterviewStatus.PASSED, executedInterview.getInterviewStatus());
        assertEquals(85, executedInterview.getInterviewScore());

        Resume afterExecuteResume = resumeService.getResume(submitResponse.getResumeId());
        assertEquals(ResumeStatus.INTERVIEW_PASSED, afterExecuteResume.getResumeStatus());

        Hire hire = hireService.getHireByResume(submitResponse.getResumeId());
        assertNotNull(hire);
        assertEquals(HireStatus.PENDING_APPROVAL, hire.getHireStatus());

        HireApproveRequest approveRequest = HireApproveRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .hireSalary("28K")
                .hireDate(LocalDate.now().plusDays(30))
                .approved(true)
                .build();

        HireApproveResponse approveResponse = hireService.approveHire(approveRequest);
        assertNotNull(approveResponse);
        assertEquals(HireStatus.APPROVED.name(), approveResponse.getStatus());

        Resume finalResume = resumeService.getResume(submitResponse.getResumeId());
        assertEquals(ResumeStatus.HIRED, finalResume.getResumeStatus());

        Candidate finalCandidate = candidateService.getCandidate(submitResponse.getCandidateId());
        assertEquals(CandidateStatus.HIRED, finalCandidate.getCandidateStatus());

        System.out.println("完整招聘流程测试通过!");
        System.out.println("职位ID: " + testPosition.getPositionId());
        System.out.println("候选人ID: " + submitResponse.getCandidateId());
        System.out.println("简历ID: " + submitResponse.getResumeId());
        System.out.println("面试ID: " + arrangeResponse.getInterviewId());
        System.out.println("录用ID: " + approveResponse.getHireId());
    }

    @Test
    void testResumeRejectionWorkflow() {
        ResumeSubmitRequest submitRequest = ResumeSubmitRequest.builder()
                .positionId(testPosition.getPositionId())
                .candidateName("王五")
                .candidatePhone("13800138002")
                .build();

        ResumeSubmitResponse submitResponse = resumeService.submitResume(submitRequest);

        ResumeScreenRequest screenRequest = ResumeScreenRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .passed(false)
                .rejectReason("不符合招聘要求")
                .build();

        Resume rejectedResume = resumeService.screenResume(screenRequest);
        assertEquals(ResumeStatus.REJECTED_SCREEN, rejectedResume.getResumeStatus());

        Candidate candidate = candidateService.getCandidate(submitResponse.getCandidateId());
        assertEquals(CandidateStatus.REJECTED, candidate.getCandidateStatus());
    }

    @Test
    void testPositionNotRecruiting() {
        Position draftPosition = positionService.createPosition(
                "测试职位",
                PositionType.TECHNICAL,
                "测试部",
                1,
                "10K-20K",
                null
        );

        ResumeSubmitRequest submitRequest = ResumeSubmitRequest.builder()
                .positionId(draftPosition.getPositionId())
                .candidateName("赵六")
                .candidatePhone("13800138003")
                .build();

        assertThrows(RuntimeException.class, () -> resumeService.submitResume(submitRequest));
    }

    @Test
    void testInterviewerUnavailable() {
        Interviewer busyInterviewer = interviewerService.createInterviewer(
                "陈七",
                "研发部",
                InterviewType.TECHNICAL
        );
        interviewerService.updateInterviewerStatus(
                busyInterviewer.getInterviewerId(),
                InterviewerStatus.UNAVAILABLE
        );

        ResumeSubmitRequest submitRequest = ResumeSubmitRequest.builder()
                .positionId(testPosition.getPositionId())
                .candidateName("孙八")
                .candidatePhone("13800138004")
                .build();

        ResumeSubmitResponse submitResponse = resumeService.submitResume(submitRequest);

        ResumeScreenRequest screenRequest = ResumeScreenRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .passed(true)
                .build();

        resumeService.screenResume(screenRequest);

        InterviewArrangeRequest arrangeRequest = InterviewArrangeRequest.builder()
                .resumeId(submitResponse.getResumeId())
                .interviewerId(busyInterviewer.getInterviewerId())
                .interviewTime(Instant.now().plusSeconds(86400))
                .build();

        assertThrows(RuntimeException.class, () -> interviewService.arrangeInterview(arrangeRequest));
    }
}
