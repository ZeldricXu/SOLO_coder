package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.config.CourseTypeConfig;
import com.fitnesscenter.dto.BookingRequest;
import com.fitnesscenter.dto.BookingResponse;
import com.fitnesscenter.model.*;
import com.fitnesscenter.repository.*;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final MemberService memberService;
    private final CourseService courseService;
    private final CoachService coachService;
    private final CourseRepository courseRepository;
    private final HistoryRepository historyRepository;
    private final StatisticRepository statisticRepository;
    private final ObjectMapper objectMapper;
    private final CourseTypeConfig courseTypeConfig;

    public BookingService(BookingRepository bookingRepository,
                          MemberService memberService,
                          CourseService courseService,
                          CoachService coachService,
                          CourseRepository courseRepository,
                          HistoryRepository historyRepository,
                          StatisticRepository statisticRepository,
                          ObjectMapper objectMapper,
                          CourseTypeConfig courseTypeConfig) {
        this.bookingRepository = bookingRepository;
        this.memberService = memberService;
        this.courseService = courseService;
        this.coachService = coachService;
        this.courseRepository = courseRepository;
        this.historyRepository = historyRepository;
        this.statisticRepository = statisticRepository;
        this.objectMapper = objectMapper;
        this.courseTypeConfig = courseTypeConfig;
    }

    @Transactional
    public BookingResponse createBooking(BookingRequest request) {
        memberService.validateMemberStatus(request.getMemberId());
        Member member = memberService.getMemberById(request.getMemberId());

        courseService.validateCourseStatus(request.getCourseId());
        Course course = courseService.getCourseById(request.getCourseId());

        validateCourseTypeForBooking(course);

        if (bookingRepository.existsByMemberIdAndCourseId(request.getMemberId(), request.getCourseId())) {
            throw new IllegalStateException("您已预约该课程");
        }

        if (course.getCourseCoach() != null) {
            coachService.validateCoachStatus(course.getCourseCoach());
        }

        boolean success = courseService.decreaseAvailableSlots(request.getCourseId());
        if (!success) {
            throw new IllegalStateException("课程名额已满");
        }

        Booking booking = new Booking();
        booking.setBookingId(IdGenerator.generateBookingId());
        booking.setMemberId(request.getMemberId());
        booking.setCourseId(request.getCourseId());
        booking.setCoachId(course.getCourseCoach());
        booking.setBookingStatus("confirmed");
        booking.setBookingTime(Instant.now());
        booking.setConfirmedAt(Instant.now());

        Booking savedBooking = bookingRepository.save(booking);

        memberService.incrementBookingCount(request.getMemberId());

        if (course.getCourseCoach() != null) {
            coachService.incrementBookingCount(course.getCourseCoach());
        }

        updateMonthlyBookingCount();

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(request.getMemberId());
            history.setActionType("BOOKING_CREATE");
            history.setActionData(objectMapper.writeValueAsString(savedBooking));
            history.setActionTime(Instant.now());
            history.setRelatedId(savedBooking.getBookingId());
            historyRepository.save(history);
        } catch (Exception e) {
        }

        return new BookingResponse(savedBooking.getBookingId(), savedBooking.getBookingStatus());
    }

    private void validateCourseTypeForBooking(Course course) {
        if (course.getCourseType() != null && !course.getCourseType().isEmpty()) {
            if (!courseTypeConfig.isTypeEnabled(course.getCourseType())) {
                throw new IllegalStateException("课程类型 '" + course.getCourseType() + "' 未启用");
            }
        }
    }

    @Transactional(readOnly = true)
    public Booking getBookingById(String bookingId) {
        return bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("预约不存在"));
    }

    @Transactional(readOnly = true)
    public List<Booking> getBookingsByMemberId(String memberId) {
        return bookingRepository.findByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public List<Booking> getBookingsByCourseId(String courseId) {
        return bookingRepository.findByCourseId(courseId);
    }

    @Transactional(readOnly = true)
    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    @Transactional
    public Booking updateBookingStatus(String bookingId, String status) {
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("预约不存在"));

        booking.setBookingStatus(status);
        return bookingRepository.save(booking);
    }

    @Transactional(readOnly = true)
    public void validateMemberHasBooking(String memberId, String courseId) {
        Optional<Booking> bookingOpt = bookingRepository.findByMemberIdAndCourseId(memberId, courseId);
        if (bookingOpt.isEmpty()) {
            throw new IllegalStateException("会员未预约该课程");
        }
        if (!"confirmed".equals(bookingOpt.get().getBookingStatus())) {
            throw new IllegalStateException("预约状态无效");
        }
    }

    private void updateMonthlyBookingCount() {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Statistic statistic = statisticRepository.findByStatMonth(month).orElseGet(() -> {
            Statistic newStat = new Statistic();
            newStat.setStatId(IdGenerator.generateStatId());
            newStat.setStatMonth(month);
            newStat.setMemberCount(0);
            newStat.setBookingCount(0);
            newStat.setTrainingCount(0);
            newStat.setTotalCalories(0);
            newStat.setPlanCount(0);
            return newStat;
        });

        statistic.setBookingCount(statistic.getBookingCount() + 1);
        statisticRepository.save(statistic);
    }
}
