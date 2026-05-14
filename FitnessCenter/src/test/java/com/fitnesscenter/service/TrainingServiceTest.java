package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.dto.TrainingRequest;
import com.fitnesscenter.dto.TrainingResponse;
import com.fitnesscenter.model.*;
import com.fitnesscenter.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("训练模块测试")
class TrainingServiceTest {

    @Mock
    private TrainingRepository trainingRepository;

    @Mock
    private MemberService memberService;

    @Mock
    private CourseService courseService;

    @Mock
    private BookingService bookingService;

    @Mock
    private PlanService planService;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StatisticRepository statisticRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private TrainingService trainingService;

    private Member vipMember;
    private Member regularMember;
    private Member expiredMember;
    private Course availableCourse;
    private Booking confirmedBooking;

    @BeforeEach
    void setUp() {
        vipMember = TestDataBuilder.buildVipMember();
        regularMember = TestDataBuilder.buildRegularMember();
        expiredMember = TestDataBuilder.buildExpiredMember();
        availableCourse = TestDataBuilder.buildYogaCourse();
        confirmedBooking = TestDataBuilder.buildConfirmedBooking(
                vipMember.getMemberId(),
                availableCourse.getCourseId(),
                TestDataBuilder.buildAvailableCoach().getCoachId()
        );
    }

    @Test
    @DisplayName("测试正常记录训练 - 中等强度")
    void testRecordTrainingWithMediumIntensity() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        when(courseService.getCourseById(availableCourse.getCourseId())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        TrainingResponse response = trainingService.recordTraining(request);

        assertNotNull(response, "响应不应该为空");
        assertEquals(300, response.getCalories(), "中等强度60分钟应该消耗300卡路里");
        verify(memberService).validateMemberStatus(vipMember.getMemberId());
        verify(bookingService).validateMemberHasBooking(vipMember.getMemberId(), availableCourse.getCourseId());
        verify(memberService).updateTrainingStats(eq(vipMember.getMemberId()), eq(300));
    }

    @Test
    @DisplayName("测试记录训练 - 高强度")
    void testRecordTrainingWithHighIntensity() {
        TrainingRequest request = TestDataBuilder.buildHighIntensityTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildHighIntensityTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        TrainingResponse response = trainingService.recordTraining(request);

        assertEquals(450, response.getCalories(), "高强度45分钟应该消耗450卡路里");
    }

    @Test
    @DisplayName("测试会员不存在时拒绝记录训练")
    void testRecordTrainingWhenMemberNotExists() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                "NON_EXISTENT_MEMBER",
                availableCourse.getCourseId()
        );

        doThrow(new IllegalArgumentException("会员不存在"))
                .when(memberService).validateMemberStatus("NON_EXISTENT_MEMBER");

        assertThrows(IllegalArgumentException.class,
                () -> trainingService.recordTraining(request),
                "会员不存在时应该抛出异常");
        verify(courseService, never()).getCourseById(anyString());
    }

    @Test
    @DisplayName("测试过期会员拒绝记录训练")
    void testRecordTrainingWhenMemberExpired() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                expiredMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doThrow(new IllegalStateException("会员已过期"))
                .when(memberService).validateMemberStatus(expiredMember.getMemberId());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> trainingService.recordTraining(request),
                "过期会员应该被拒绝记录训练");
        assertTrue(exception.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("测试课程不存在时拒绝记录训练")
    void testRecordTrainingWhenCourseNotExists() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                "NON_EXISTENT_COURSE"
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doThrow(new IllegalArgumentException("课程不存在"))
                .when(courseService).getCourseById("NON_EXISTENT_COURSE");

        assertThrows(IllegalArgumentException.class,
                () -> trainingService.recordTraining(request),
                "课程不存在时应该抛出异常");
    }

    @Test
    @DisplayName("测试未预约课程拒绝记录训练")
    void testRecordTrainingWhenNotBooked() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doThrow(new IllegalStateException("会员未预约该课程"))
                .when(bookingService).validateMemberHasBooking(vipMember.getMemberId(), availableCourse.getCourseId());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> trainingService.recordTraining(request),
                "未预约课程应该被拒绝记录训练");
        assertTrue(exception.getMessage().contains("未预约"));
    }

    @Test
    @DisplayName("测试训练记录后更新计划进度")
    void testRecordTrainingUpdatesPlanProgress() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        trainingService.recordTraining(request);

        verify(planService).updatePlanProgress(eq(vipMember.getMemberId()), eq(300));
    }

    @Test
    @DisplayName("测试训练记录后更新会员统计")
    void testRecordTrainingUpdatesMemberStats() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        trainingService.recordTraining(request);

        verify(memberService).updateTrainingStats(eq(vipMember.getMemberId()), eq(300));
    }

    @Test
    @DisplayName("测试查询训练记录")
    void testGetTrainingById() {
        Training training = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        when(trainingRepository.findByTrainingId(training.getTrainingId())).thenReturn(Optional.of(training));

        Training foundTraining = trainingService.getTrainingById(training.getTrainingId());

        assertNotNull(foundTraining, "训练记录应该存在");
        assertEquals(training.getTrainingId(), foundTraining.getTrainingId());
        assertEquals(300, foundTraining.getTrainingCalories());
    }

    @Test
    @DisplayName("测试查询不存在的训练记录")
    void testGetTrainingByIdWhenNotExists() {
        when(trainingRepository.findByTrainingId("NON_EXISTENT_TRAINING")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> trainingService.getTrainingById("NON_EXISTENT_TRAINING"),
                "不存在的训练记录应该抛出异常");
    }

    @Test
    @DisplayName("测试查询会员的所有训练记录")
    void testGetTrainingsByMemberId() {
        Training training1 = TestDataBuilder.buildMediumTraining(vipMember.getMemberId(), availableCourse.getCourseId());
        Training training2 = TestDataBuilder.buildHighIntensityTraining(vipMember.getMemberId(), availableCourse.getCourseId());
        when(trainingRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Arrays.asList(training1, training2));

        java.util.List<Training> trainings = trainingService.getTrainingsByMemberId(vipMember.getMemberId());

        assertEquals(2, trainings.size(), "应该返回2条训练记录");
    }

    @Test
    @DisplayName("测试查询会员无训练记录")
    void testGetTrainingsByMemberIdWhenEmpty() {
        when(trainingRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Collections.emptyList());

        java.util.List<Training> trainings = trainingService.getTrainingsByMemberId(regularMember.getMemberId());

        assertTrue(trainings.isEmpty(), "应该返回空列表");
    }

    @Test
    @DisplayName("测试查询所有训练记录")
    void testGetAllTrainings() {
        Training training1 = TestDataBuilder.buildMediumTraining(vipMember.getMemberId(), availableCourse.getCourseId());
        Training training2 = TestDataBuilder.buildHighIntensityTraining(regularMember.getMemberId(), availableCourse.getCourseId());
        when(trainingRepository.findAll()).thenReturn(Arrays.asList(training1, training2));

        java.util.List<Training> trainings = trainingService.getAllTrainings();

        assertEquals(2, trainings.size(), "应该返回2条训练记录");
    }

    @Test
    @DisplayName("测试训练历史记录")
    void testTrainingHistoryIsRecorded() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());

        trainingService.recordTraining(request);

        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试训练统计数据更新")
    void testTrainingStatisticsAreUpdated() {
        TrainingRequest request = TestDataBuilder.buildMediumTrainingRequest(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );
        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        trainingService.recordTraining(request);

        verify(statisticRepository).save(any(Statistic.class));
    }

    @Test
    @DisplayName("测试默认训练强度为中等")
    void testDefaultTrainingIntensity() {
        TrainingRequest request = new TrainingRequest();
        request.setMemberId(vipMember.getMemberId());
        request.setCourseId(availableCourse.getCourseId());
        request.setTrainingDuration(60);
        request.setTrainingIntensity(null);

        Training savedTraining = TestDataBuilder.buildMediumTraining(
                vipMember.getMemberId(),
                availableCourse.getCourseId()
        );

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        doNothing().when(bookingService).validateMemberHasBooking(anyString(), anyString());
        when(trainingRepository.save(any(Training.class))).thenReturn(savedTraining);
        doNothing().when(memberService).updateTrainingStats(anyString(), anyInt());
        doNothing().when(planService).updatePlanProgress(anyString(), anyInt());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        trainingService.recordTraining(request);

        verify(trainingRepository).save(any(Training.class));
    }
}
