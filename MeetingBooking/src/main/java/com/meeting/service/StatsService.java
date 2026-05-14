package com.meeting.service;

import com.meeting.dto.StatsResponse;
import com.meeting.entity.Meeting;
import com.meeting.entity.MeetingStats;
import com.meeting.repository.AttendeeRepository;
import com.meeting.repository.MeetingRepository;
import com.meeting.repository.MeetingStatsRepository;
import com.meeting.repository.ReminderRepository;
import com.meeting.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final MeetingStatsRepository statsRepository;
    private final MeetingRepository meetingRepository;
    private final AttendeeRepository attendeeRepository;
    private final ReminderRepository reminderRepository;

    public MeetingStats getStatsByMonth(String month) {
        return statsRepository.findByStatMonth(month)
                .orElseGet(() -> createEmptyStats(month));
    }

    private MeetingStats createEmptyStats(String month) {
        return MeetingStats.builder()
                .statId(IdGenerator.generateStatId())
                .statMonth(month)
                .meetingCount(0)
                .totalDurationMinutes(0L)
                .attendeeCount(0)
                .confirmedAttendeeCount(0)
                .reminderSentCount(0)
                .cancelledCount(0)
                .build();
    }

    @Transactional
    public MeetingStats getOrCreateStatsForMonth(LocalDateTime dateTime) {
        String month = formatMonth(dateTime);
        return statsRepository.findByStatMonth(month)
                .orElseGet(() -> {
                    MeetingStats stats = createEmptyStats(month);
                    return statsRepository.save(stats);
                });
    }

    private String formatMonth(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    @Transactional
    public void incrementMeetingCount(LocalDateTime meetingTime, long durationMinutes) {
        MeetingStats stats = getOrCreateStatsForMonth(meetingTime);
        stats.setMeetingCount(stats.getMeetingCount() + 1);
        stats.setTotalDurationMinutes(stats.getTotalDurationMinutes() + durationMinutes);
        statsRepository.save(stats);
        log.info("更新会议统计: month={}, meetingCount={}", stats.getStatMonth(), stats.getMeetingCount());
    }

    @Transactional
    public void incrementAttendeeCount(LocalDateTime meetingTime) {
        MeetingStats stats = getOrCreateStatsForMonth(meetingTime);
        stats.setAttendeeCount(stats.getAttendeeCount() + 1);
        statsRepository.save(stats);
    }

    @Transactional
    public void incrementConfirmedAttendee(LocalDateTime meetingTime) {
        MeetingStats stats = getOrCreateStatsForMonth(meetingTime);
        stats.setConfirmedAttendeeCount(stats.getConfirmedAttendeeCount() + 1);
        statsRepository.save(stats);
        log.info("更新确认参会统计: month={}, confirmedCount={}", stats.getStatMonth(), stats.getConfirmedAttendeeCount());
    }

    @Transactional
    public void incrementReminderSent(LocalDateTime meetingTime) {
        MeetingStats stats = getOrCreateStatsForMonth(meetingTime);
        stats.setReminderSentCount(stats.getReminderSentCount() + 1);
        statsRepository.save(stats);
    }

    @Transactional
    public void incrementCancelledCount(LocalDateTime meetingTime) {
        MeetingStats stats = getOrCreateStatsForMonth(meetingTime);
        stats.setCancelledCount(stats.getCancelledCount() + 1);
        statsRepository.save(stats);
        log.info("更新取消会议统计: month={}, cancelledCount={}", stats.getStatMonth(), stats.getCancelledCount());
    }

    public StatsResponse getStatsResponse(String month) {
        MeetingStats stats = getStatsByMonth(month);

        YearMonth ym = YearMonth.parse(month);
        LocalDateTime startOfMonth = ym.atDay(1).atStartOfDay();
        LocalDateTime endOfMonth = ym.atEndOfMonth().atTime(23, 59, 59);

        Map<String, Long> roomUsage = new HashMap<>();
        List<Object[]> roomStats = meetingRepository.countMeetingsByRoomInRange(startOfMonth, endOfMonth);
        for (Object[] result : roomStats) {
            String roomId = (String) result[0];
            Long count = (Long) result[1];
            roomUsage.put(roomId, count);
        }

        Map<String, Integer> typeDistribution = new HashMap<>();
        List<Object[]> typeStats = meetingRepository.countMeetingsByTypeInRange(startOfMonth, endOfMonth);
        for (Object[] result : typeStats) {
            String type = (String) result[0];
            Long count = (Long) result[1];
            typeDistribution.put(type, count.intValue());
        }

        Double avgAttendees = stats.getMeetingCount() > 0
                ? (double) stats.getAttendeeCount() / stats.getMeetingCount()
                : 0.0;

        return StatsResponse.builder()
                .statMonth(stats.getStatMonth())
                .meetingCount(stats.getMeetingCount())
                .totalDurationMinutes(stats.getTotalDurationMinutes())
                .attendeeCount(stats.getAttendeeCount())
                .confirmedAttendeeCount(stats.getConfirmedAttendeeCount())
                .reminderSentCount(stats.getReminderSentCount())
                .cancelledCount(stats.getCancelledCount())
                .averageAttendeesPerMeeting(avgAttendees)
                .roomUsage(roomUsage)
                .meetingTypeDistribution(typeDistribution)
                .build();
    }

    public StatsResponse getCurrentMonthStats() {
        String currentMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return getStatsResponse(currentMonth);
    }

    public List<MeetingStats> getAllStats() {
        return statsRepository.findAll();
    }

    @Transactional
    public MeetingStats saveStats(MeetingStats stats) {
        return statsRepository.save(stats);
    }

    public long countTotalMeetings() {
        return meetingRepository.count();
    }

    public long countMeetingsByStatus(String status) {
        return meetingRepository.countByMeetingStatus(status);
    }
}
