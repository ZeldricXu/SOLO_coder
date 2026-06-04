package com.flowplatform.common;

import com.flowplatform.mapper.SysConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class WorkingHoursCalculator {

    private static final Set<DayOfWeek> DEFAULT_WEEKENDS = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    private static final LocalTime DEFAULT_WORK_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_WORK_END = LocalTime.of(18, 0);

    private final SysConfigMapper configMapper;

    public WorkingHoursCalculator(SysConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public WorkingHoursConfig getConfig() {
        try {
            String workStartStr = safeGetValue("working_hours.start");
            String workEndStr = safeGetValue("working_hours.end");
            String weekendStr = safeGetValue("working_hours.weekends");

            LocalTime workStart = workStartStr != null
                    ? LocalTime.parse(workStartStr, DateTimeFormatter.ofPattern("HH:mm"))
                    : DEFAULT_WORK_START;
            LocalTime workEnd = workEndStr != null
                    ? LocalTime.parse(workEndStr, DateTimeFormatter.ofPattern("HH:mm"))
                    : DEFAULT_WORK_END;

            Set<DayOfWeek> weekends = DEFAULT_WEEKENDS;
            if (weekendStr != null && !weekendStr.trim().isEmpty()) {
                weekends = new HashSet<>();
                for (String day : weekendStr.split(",")) {
                    try {
                        weekends.add(DayOfWeek.of(Integer.parseInt(day.trim())));
                    } catch (Exception e) {
                        log.warn("Invalid weekend config value: {}", day);
                    }
                }
            }

            return new WorkingHoursConfig(workStart, workEnd, weekends, true);
        } catch (Exception e) {
            log.warn("Failed to load working hours config, using defaults: {}", e.getMessage());
            return new WorkingHoursConfig(DEFAULT_WORK_START, DEFAULT_WORK_END, DEFAULT_WEEKENDS, false);
        }
    }

    private String safeGetValue(String key) {
        try {
            return configMapper.getValueByKey(key);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isWorkingHoursConfigured() {
        return getConfig().configured();
    }

    public double calculateWorkingHours(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0.0;
        }
        if (!isWorkingHoursConfigured()) {
            return Duration.between(start, end).toMinutes() / 60.0;
        }
        return calculateWorkingHours(start, end, getConfig());
    }

    public double calculateWorkingHours(LocalDateTime start, LocalDateTime end, WorkingHoursConfig config) {
        if (start.isAfter(end)) {
            return 0.0;
        }
        if (config == null) {
            return Duration.between(start, end).toMinutes() / 60.0;
        }

        double totalMinutes = 0.0;
        LocalDateTime current = start;

        while (current.isBefore(end)) {
            LocalDate currentDate = current.toLocalDate();
            LocalDateTime dayWorkStart = LocalDateTime.of(currentDate, config.workStart());
            LocalDateTime dayWorkEnd = LocalDateTime.of(currentDate, config.workEnd());

            if (isWorkDay(current.getDayOfWeek(), config)) {
                LocalDateTime effectiveStart = current.isAfter(dayWorkStart) ? current : dayWorkStart;
                LocalDateTime effectiveEnd = end.isBefore(dayWorkEnd) ? end : dayWorkEnd;

                if (effectiveStart.isBefore(effectiveEnd)) {
                    totalMinutes += Duration.between(effectiveStart, effectiveEnd).toMinutes();
                }
            }

            current = LocalDateTime.of(currentDate.plusDays(1), config.workStart());
        }

        return totalMinutes / 60.0;
    }

    private boolean isWorkDay(DayOfWeek dayOfWeek, WorkingHoursConfig config) {
        return !config.weekends().contains(dayOfWeek);
    }

    public record WorkingHoursConfig(
            LocalTime workStart,
            LocalTime workEnd,
            Set<DayOfWeek> weekends,
            boolean configured
    ) {}
}
