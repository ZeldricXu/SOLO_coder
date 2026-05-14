package com.schedulebook.testdata;

import com.schedulebook.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class TestDataBuilder {

    public static final String DEFAULT_RESOURCE_ID = "room_001";
    public static final String DEFAULT_RESOURCE_NAME = "会议室A";
    public static final String DEFAULT_RESOURCE_TYPE = "room";
    public static final String DEFAULT_USER_ID = "user_10086";
    public static final String DEFAULT_BOOKING_ID = "booking_001";
    public static final String DEFAULT_SCHEDULE_ID = "schedule_room_001";

    public static Resource buildResource() {
        return buildResource(DEFAULT_RESOURCE_ID, DEFAULT_RESOURCE_NAME, DEFAULT_RESOURCE_TYPE);
    }

    public static Resource buildResource(String resourceId, String resourceName, String resourceType) {
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceId(resourceId);
        resource.setResourceName(resourceName);
        resource.setResourceType(resourceType);
        resource.setResourceCapacity(10);
        resource.setResourceStatus("available");
        resource.setResourceLocation("办公区A");
        resource.setAvailableHours("09:00-18:00");
        resource.setPriority(1);
        resource.setCurrentOccupancy(0);
        return resource;
    }

    public static List<Resource> buildMultipleResources(int count) {
        List<Resource> resources = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Resource resource = new Resource();
            resource.setId((long) i);
            resource.setResourceId("room_00" + i);
            resource.setResourceName("会议室" + i);
            resource.setResourceType("room");
            resource.setResourceCapacity(10);
            resource.setResourceStatus("available");
            resource.setResourceLocation("办公区A");
            resource.setAvailableHours("09:00-18:00");
            resource.setPriority(count - i + 1);
            resource.setCurrentOccupancy(0);
            resources.add(resource);
        }
        return resources;
    }

    public static Booking buildBooking() {
        return buildBooking(DEFAULT_BOOKING_ID, DEFAULT_USER_ID, DEFAULT_RESOURCE_TYPE);
    }

    public static Booking buildBooking(String bookingId, String userId, String resourceType) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setBookingId(bookingId);
        booking.setBookingType("appointment");
        booking.setUserId(userId);
        booking.setResourceType(resourceType);
        booking.setResourceId(DEFAULT_RESOURCE_ID);
        booking.setBookingDate(LocalDate.of(2026, 5, 5));
        booking.setBookingTime(LocalTime.of(10, 0));
        booking.setBookingDuration(60);
        booking.setBookingStatus("pending");
        booking.setCreatedAt(LocalDateTime.now());
        return booking;
    }

    public static Booking buildConfirmedBooking() {
        Booking booking = buildBooking();
        booking.setBookingStatus("confirmed");
        booking.setConfirmedAt(LocalDateTime.now());
        return booking;
    }

    public static Booking buildBookingWithDuration(int durationMinutes) {
        Booking booking = buildBooking();
        booking.setBookingDuration(durationMinutes);
        return booking;
    }

    public static List<Booking> buildMultipleBookings(int count) {
        List<Booking> bookings = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Booking booking = new Booking();
            booking.setId((long) i);
            booking.setBookingId("booking_00" + i);
            booking.setBookingType("appointment");
            booking.setUserId("user_" + (10000 + i));
            booking.setResourceType(DEFAULT_RESOURCE_TYPE);
            booking.setResourceId(DEFAULT_RESOURCE_ID);
            booking.setBookingDate(LocalDate.of(2026, 5, 5));
            booking.setBookingTime(LocalTime.of(9 + i, 0));
            booking.setBookingDuration(60);
            booking.setBookingStatus("pending");
            booking.setCreatedAt(LocalDateTime.now());
            bookings.add(booking);
        }
        return bookings;
    }

    public static Schedule buildSchedule() {
        return buildSchedule(DEFAULT_SCHEDULE_ID, DEFAULT_RESOURCE_ID, LocalDate.of(2026, 5, 5));
    }

    public static Schedule buildSchedule(String scheduleId, String resourceId, LocalDate scheduleDate) {
        Schedule schedule = new Schedule();
        schedule.setId(1L);
        schedule.setScheduleId(scheduleId);
        schedule.setResourceId(resourceId);
        schedule.setScheduleDate(scheduleDate);
        schedule.setMaxBookingPerSlot(1);
        schedule.setCreatedAt(LocalDateTime.now());
        schedule.setUpdatedAt(LocalDateTime.now());
        return schedule;
    }

    public static List<ScheduleSlot> buildScheduleSlots() {
        return buildScheduleSlotsWithStatus("available");
    }

    public static List<ScheduleSlot> buildScheduleSlotsWithStatus(String status) {
        List<ScheduleSlot> slots = new ArrayList<>();
        LocalTime[] times = {
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                LocalTime.of(14, 0),
                LocalTime.of(15, 0)
        };

        for (int i = 0; i < times.length; i++) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setId((long) (i + 1));
            slot.setSlotTime(times[i]);
            slot.setSlotStatus(status);
            slot.setCurrentBookings("available".equals(status) ? 0 : 1);
            slots.add(slot);
        }
        return slots;
    }

    public static ScheduleSlot buildScheduleSlot(LocalTime time, String status) {
        ScheduleSlot slot = new ScheduleSlot();
        slot.setId(1L);
        slot.setSlotTime(time);
        slot.setSlotStatus(status);
        slot.setCurrentBookings("available".equals(status) ? 0 : 1);
        return slot;
    }

    public static ScheduleSlot buildBookedScheduleSlot(LocalTime time, String bookingId) {
        ScheduleSlot slot = buildScheduleSlot(time, "booked");
        slot.setBookingId(bookingId);
        slot.setCurrentBookings(1);
        return slot;
    }

    public static Dispatch buildDispatch() {
        return buildDispatch(DEFAULT_BOOKING_ID, DEFAULT_RESOURCE_ID);
    }

    public static Dispatch buildDispatch(String bookingId, String resourceId) {
        Dispatch dispatch = new Dispatch();
        dispatch.setId(1L);
        dispatch.setDispatchId("dispatch_001");
        dispatch.setBookingId(bookingId);
        dispatch.setResourceId(resourceId);
        dispatch.setDispatchTime(LocalTime.of(10, 0));
        dispatch.setDispatchStatus("assigned");
        dispatch.setDispatchedAt(LocalDateTime.now());
        return dispatch;
    }

    public static Reminder buildReminder() {
        return buildReminder(DEFAULT_BOOKING_ID, "before_time", LocalTime.of(9, 30));
    }

    public static Reminder buildReminder(String bookingId, String reminderType, LocalTime reminderTime) {
        Reminder reminder = new Reminder();
        reminder.setId(1L);
        reminder.setReminderId("reminder_001");
        reminder.setBookingId(bookingId);
        reminder.setReminderType(reminderType);
        reminder.setReminderTime(reminderTime);
        reminder.setReminderChannel("sms");
        reminder.setReminderStatus("pending");
        reminder.setCreatedAt(LocalDateTime.now());
        return reminder;
    }

    public static List<Reminder> buildMultipleReminders(String bookingId, int count) {
        List<Reminder> reminders = new ArrayList<>();
        LocalTime baseTime = LocalTime.of(10, 0);
        String[] types = {"before_day", "before_hour", "on_time"};

        for (int i = 0; i < count && i < types.length; i++) {
            Reminder reminder = new Reminder();
            reminder.setId((long) (i + 1));
            reminder.setReminderId("reminder_00" + (i + 1));
            reminder.setBookingId(bookingId);
            reminder.setReminderType(types[i]);
            reminder.setReminderTime(baseTime.minusMinutes((i + 1) * 30));
            reminder.setReminderChannel("sms");
            reminder.setReminderStatus("pending");
            reminder.setCreatedAt(LocalDateTime.now());
            reminders.add(reminder);
        }
        return reminders;
    }

    public static CancelRecord buildCancelRecord() {
        return buildCancelRecord(DEFAULT_BOOKING_ID, "时间冲突", DEFAULT_USER_ID);
    }

    public static CancelRecord buildCancelRecord(String bookingId, String reason, String cancelBy) {
        CancelRecord cancelRecord = new CancelRecord();
        cancelRecord.setId(1L);
        cancelRecord.setCancelId("cancel_001");
        cancelRecord.setBookingId(bookingId);
        cancelRecord.setCancelReason(reason);
        cancelRecord.setCancelTime(LocalDateTime.now());
        cancelRecord.setCancelBy(cancelBy);
        return cancelRecord;
    }

    public static BookingHistory buildBookingHistory() {
        return buildBookingHistory(DEFAULT_BOOKING_ID, DEFAULT_USER_ID, "create");
    }

    public static BookingHistory buildBookingHistory(String bookingId, String userId, String actionType) {
        BookingHistory history = new BookingHistory();
        history.setId(1L);
        history.setHistoryId("history_001");
        history.setBookingId(bookingId);
        history.setUserId(userId);
        history.setResourceType(DEFAULT_RESOURCE_TYPE);
        history.setResourceId(DEFAULT_RESOURCE_ID);
        history.setBookingDate(LocalDate.of(2026, 5, 5));
        history.setBookingTime(LocalTime.of(10, 0));
        history.setFinalStatus("confirmed");
        history.setActionType(actionType);
        history.setActionTime(LocalDateTime.now());
        history.setActionDetail("测试操作");
        return history;
    }

    public static BookingStatistics buildStatistics() {
        BookingStatistics statistics = new BookingStatistics();
        statistics.setId(1L);
        statistics.setStatId("stat_001");
        statistics.setStatDate(LocalDate.now());
        statistics.setTotalBookings(100);
        statistics.setConfirmedBookings(80);
        statistics.setCancelledBookings(10);
        statistics.setResourceUtilization(70);
        statistics.setUpdatedAt(LocalDateTime.now());
        return statistics;
    }

    public static class UrgencyLevel {
        public static final String HIGH = "high";
        public static final String MEDIUM = "medium";
        public static final String LOW = "low";
    }

    public static class ReminderType {
        public static final String BEFORE_DAY = "before_day";
        public static final String BEFORE_HOUR = "before_hour";
        public static final String ON_TIME = "on_time";
    }
}
