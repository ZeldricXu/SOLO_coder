package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.dto.BookingRequest;
import com.fitnesscenter.dto.BookingResponse;
import com.fitnesscenter.lock.LockService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预约模块测试")
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private MemberService memberService;

    @Mock
    private CourseService courseService;

    @Mock
    private CoachService coachService;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private HistoryRepository historyRepository;

    @Mock
    private StatisticRepository statisticRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private BookingService bookingService;

    private Member vipMember;
    private Member regularMember;
    private Member expiredMember;
    private Member frozenMember;
    private Course availableCourse;
    private Course fullCourse;
    private Course cancelledCourse;
    private Coach availableCoach;
    private Coach unavailableCoach;

    @BeforeEach
    void setUp() {
        vipMember = TestDataBuilder.buildVipMember();
        regularMember = TestDataBuilder.buildRegularMember();
        expiredMember = TestDataBuilder.buildExpiredMember();
        frozenMember = TestDataBuilder.buildFrozenMember();
        availableCourse = TestDataBuilder.buildYogaCourse();
        fullCourse = TestDataBuilder.buildFullCapacityCourse();
        cancelledCourse = TestDataBuilder.buildCancelledCourse();
        availableCoach = TestDataBuilder.buildAvailableCoach();
        unavailableCoach = TestDataBuilder.buildUnavailableCoach();
    }

    @Test
    @DisplayName("测试正常创建预约 - VIP会员预约")
    void testCreateBookingForVipMember() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), availableCourse.getCourseId());
        Booking savedBooking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(vipMember.getMemberId())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(availableCourse.getCourseId())).thenReturn(availableCourse);
        when(bookingRepository.existsByMemberIdAndCourseId(anyString(), anyString())).thenReturn(false);
        doNothing().when(coachService).validateCoachStatus(anyString());
        when(courseService.decreaseAvailableSlots(anyString())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        doNothing().when(memberService).incrementBookingCount(anyString());
        doNothing().when(coachService).incrementBookingCount(anyString());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());
        when(historyRepository.save(any(History.class))).thenReturn(new History());

        BookingResponse response = bookingService.createBooking(request);

        assertNotNull(response, "响应不应该为空");
        assertEquals("confirmed", response.getStatus(), "预约状态应该为confirmed");
        verify(memberService).validateMemberStatus(vipMember.getMemberId());
        verify(courseService).validateCourseStatus(availableCourse.getCourseId());
        verify(courseService).decreaseAvailableSlots(availableCourse.getCourseId());
        verify(memberService).incrementBookingCount(vipMember.getMemberId());
    }

    @Test
    @DisplayName("测试会员不存在时拒绝预约")
    void testCreateBookingWhenMemberNotExists() {
        BookingRequest request = TestDataBuilder.buildBookingRequest("NON_EXISTENT_MEMBER", availableCourse.getCourseId());

        doThrow(new IllegalArgumentException("会员不存在"))
                .when(memberService).validateMemberStatus("NON_EXISTENT_MEMBER");

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request),
                "会员不存在时应该抛出异常");
        verify(courseService, never()).validateCourseStatus(anyString());
    }

    @Test
    @DisplayName("测试过期会员拒绝预约")
    void testCreateBookingWhenMemberExpired() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(expiredMember.getMemberId(), availableCourse.getCourseId());

        doThrow(new IllegalStateException("会员已过期"))
                .when(memberService).validateMemberStatus(expiredMember.getMemberId());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bookingService.createBooking(request),
                "过期会员应该被拒绝预约");
        assertTrue(exception.getMessage().contains("过期"));
    }

    @Test
    @DisplayName("测试冻结会员拒绝预约")
    void testCreateBookingWhenMemberFrozen() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(frozenMember.getMemberId(), availableCourse.getCourseId());

        doThrow(new IllegalStateException("会员已冻结，不可用"))
                .when(memberService).validateMemberStatus(frozenMember.getMemberId());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bookingService.createBooking(request),
                "冻结会员应该被拒绝预约");
        assertTrue(exception.getMessage().contains("冻结"));
    }

    @Test
    @DisplayName("测试课程不存在时拒绝预约")
    void testCreateBookingWhenCourseNotExists() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), "NON_EXISTENT_COURSE");

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doThrow(new IllegalArgumentException("课程不存在"))
                .when(courseService).validateCourseStatus("NON_EXISTENT_COURSE");

        assertThrows(IllegalArgumentException.class, () -> bookingService.createBooking(request),
                "课程不存在时应该抛出异常");
    }

    @Test
    @DisplayName("测试已取消课程拒绝预约")
    void testCreateBookingWhenCourseCancelled() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), cancelledCourse.getCourseId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doThrow(new IllegalStateException("课程已取消"))
                .when(courseService).validateCourseStatus(cancelledCourse.getCourseId());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bookingService.createBooking(request),
                "已取消课程应该被拒绝预约");
        assertTrue(exception.getMessage().contains("取消"));
    }

    @Test
    @DisplayName("测试名额已满时拒绝预约")
    void testCreateBookingWhenCourseFull() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), fullCourse.getCourseId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(fullCourse.getCourseId())).thenReturn(fullCourse);
        when(bookingRepository.existsByMemberIdAndCourseId(anyString(), anyString())).thenReturn(false);
        doNothing().when(coachService).validateCoachStatus(anyString());
        when(courseService.decreaseAvailableSlots(fullCourse.getCourseId())).thenReturn(false);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bookingService.createBooking(request),
                "名额已满时应该被拒绝预约");
        assertTrue(exception.getMessage().contains("名额已满"));
    }

    @Test
    @DisplayName("测试已预约课程重复预约")
    void testCreateBookingWhenAlreadyBooked() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), availableCourse.getCourseId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        when(bookingRepository.existsByMemberIdAndCourseId(vipMember.getMemberId(), availableCourse.getCourseId())).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> bookingService.createBooking(request),
                "已预约课程不应该能重复预约");
        assertTrue(exception.getMessage().contains("已预约"));
    }

    @Test
    @DisplayName("测试教练不可用时拒绝预约")
    void testCreateBookingWhenCoachUnavailable() {
        Course courseWithUnavailableCoach = TestDataBuilder.buildYogaCourse();
        courseWithUnavailableCoach.setCourseCoach(unavailableCoach.getCoachId());
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), courseWithUnavailableCoach.getCourseId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(anyString())).thenReturn(courseWithUnavailableCoach);
        when(bookingRepository.existsByMemberIdAndCourseId(anyString(), anyString())).thenReturn(false);
        doThrow(new IllegalStateException("教练不可用"))
                .when(coachService).validateCoachStatus(unavailableCoach.getCoachId());

        assertThrows(IllegalStateException.class, () -> bookingService.createBooking(request),
                "教练不可用时应该被拒绝预约");
    }

    @Test
    @DisplayName("测试名额扣减成功")
    void testSlotDecreaseSuccess() {
        int initialSlots = availableCourse.getCourseAvailable();

        when(courseService.decreaseAvailableSlots(availableCourse.getCourseId())).thenReturn(true);

        boolean decreased = courseService.decreaseAvailableSlots(availableCourse.getCourseId());

        assertTrue(decreased, "名额应该扣减成功");
    }

    @Test
    @DisplayName("测试名额扣减失败（已满）")
    void testSlotDecreaseFailureWhenFull() {
        when(courseService.decreaseAvailableSlots(fullCourse.getCourseId())).thenReturn(false);

        boolean decreased = courseService.decreaseAvailableSlots(fullCourse.getCourseId());

        assertFalse(decreased, "名额已满时扣减应该失败");
    }

    @Test
    @DisplayName("测试预约状态查询")
    void testGetBookingById() {
        Booking booking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());
        when(bookingRepository.findByBookingId(booking.getBookingId())).thenReturn(Optional.of(booking));

        Booking foundBooking = bookingService.getBookingById(booking.getBookingId());

        assertNotNull(foundBooking, "预约应该存在");
        assertEquals(booking.getBookingId(), foundBooking.getBookingId());
        assertEquals("confirmed", foundBooking.getBookingStatus());
    }

    @Test
    @DisplayName("测试查询不存在的预约")
    void testGetBookingByIdWhenNotExists() {
        when(bookingRepository.findByBookingId("NON_EXISTENT_BOOKING")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> bookingService.getBookingById("NON_EXISTENT_BOOKING"),
                "不存在的预约应该抛出异常");
    }

    @Test
    @DisplayName("测试查询会员的所有预约")
    void testGetBookingsByMemberId() {
        Booking booking1 = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());
        Booking booking2 = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), fullCourse.getCourseId(), unavailableCoach.getCoachId());
        when(bookingRepository.findByMemberId(vipMember.getMemberId())).thenReturn(Arrays.asList(booking1, booking2));

        java.util.List<Booking> bookings = bookingService.getBookingsByMemberId(vipMember.getMemberId());

        assertEquals(2, bookings.size(), "应该返回2个预约");
    }

    @Test
    @DisplayName("测试查询会员无预约")
    void testGetBookingsByMemberIdWhenEmpty() {
        when(bookingRepository.findByMemberId(regularMember.getMemberId())).thenReturn(Collections.emptyList());

        java.util.List<Booking> bookings = bookingService.getBookingsByMemberId(regularMember.getMemberId());

        assertTrue(bookings.isEmpty(), "应该返回空列表");
    }

    @Test
    @DisplayName("测试更新预约状态")
    void testUpdateBookingStatus() {
        Booking booking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());
        when(bookingRepository.findByBookingId(booking.getBookingId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Booking updatedBooking = bookingService.updateBookingStatus(booking.getBookingId(), "cancelled");

        assertEquals("cancelled", updatedBooking.getBookingStatus(), "状态应该被更新为cancelled");
    }

    @Test
    @DisplayName("测试验证会员已预约课程")
    void testValidateMemberHasBooking() {
        when(bookingRepository.findByMemberIdAndCourseId(vipMember.getMemberId(), availableCourse.getCourseId()))
                .thenReturn(Optional.of(TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId())));

        assertDoesNotThrow(() -> bookingService.validateMemberHasBooking(vipMember.getMemberId(), availableCourse.getCourseId()),
                "有预约时不应该抛出异常");
    }

    @Test
    @DisplayName("测试验证会员未预约课程")
    void testValidateMemberHasBookingWhenNotBooked() {
        when(bookingRepository.findByMemberIdAndCourseId(regularMember.getMemberId(), availableCourse.getCourseId()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> bookingService.validateMemberHasBooking(regularMember.getMemberId(), availableCourse.getCourseId()),
                "未预约时应该抛出异常");
    }

    @Test
    @DisplayName("测试验证会员有取消的预约")
    void testValidateMemberHasBookingWhenCancelled() {
        Booking cancelledBooking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());
        cancelledBooking.setBookingStatus("cancelled");
        when(bookingRepository.findByMemberIdAndCourseId(vipMember.getMemberId(), availableCourse.getCourseId()))
                .thenReturn(Optional.of(cancelledBooking));

        assertThrows(IllegalStateException.class,
                () -> bookingService.validateMemberHasBooking(vipMember.getMemberId(), availableCourse.getCourseId()),
                "取消的预约应该抛出异常");
    }

    @Test
    @DisplayName("测试预约历史记录")
    void testBookingHistoryIsRecorded() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), availableCourse.getCourseId());
        Booking savedBooking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        when(bookingRepository.existsByMemberIdAndCourseId(anyString(), anyString())).thenReturn(false);
        doNothing().when(coachService).validateCoachStatus(anyString());
        when(courseService.decreaseAvailableSlots(anyString())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        doNothing().when(memberService).incrementBookingCount(anyString());
        doNothing().when(coachService).incrementBookingCount(anyString());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());

        bookingService.createBooking(request);

        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("测试统计数据更新")
    void testStatisticsAreUpdated() {
        BookingRequest request = TestDataBuilder.buildBookingRequest(vipMember.getMemberId(), availableCourse.getCourseId());
        Booking savedBooking = TestDataBuilder.buildConfirmedBooking(vipMember.getMemberId(), availableCourse.getCourseId(), availableCoach.getCoachId());

        doNothing().when(memberService).validateMemberStatus(anyString());
        when(memberService.getMemberById(anyString())).thenReturn(vipMember);
        doNothing().when(courseService).validateCourseStatus(anyString());
        when(courseService.getCourseById(anyString())).thenReturn(availableCourse);
        when(bookingRepository.existsByMemberIdAndCourseId(anyString(), anyString())).thenReturn(false);
        doNothing().when(coachService).validateCoachStatus(anyString());
        when(courseService.decreaseAvailableSlots(anyString())).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        doNothing().when(memberService).incrementBookingCount(anyString());
        doNothing().when(coachService).incrementBookingCount(anyString());
        when(statisticRepository.findByStatMonth(anyString())).thenReturn(Optional.empty());
        when(statisticRepository.save(any(Statistic.class))).thenReturn(new Statistic());

        bookingService.createBooking(request);

        verify(statisticRepository).save(any(Statistic.class));
    }
}
