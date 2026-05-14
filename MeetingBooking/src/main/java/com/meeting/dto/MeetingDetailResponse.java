package com.meeting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeetingDetailResponse {
    private String meetingId;
    private String roomId;
    private String roomName;
    private String roomLocation;
    private String meetingTopic;
    private String meetingType;
    private String meetingStatus;
    private LocalDateTime meetingStart;
    private LocalDateTime meetingEnd;
    private String organizerId;
    private String organizerName;
    private String description;
    private List<AttendeeDetail> attendees;
    private List<DeviceInfo> devices;
    private List<ReminderInfo> reminders;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttendeeDetail {
        private String attendeeId;
        private String userId;
        private String userName;
        private String userEmail;
        private String attendeeStatus;
        private LocalDateTime attendeeTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeviceInfo {
        private String deviceId;
        private String deviceType;
        private String deviceName;
        private String deviceStatus;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ReminderInfo {
        private String reminderId;
        private String reminderType;
        private LocalDateTime reminderTime;
        private String reminderStatus;
    }
}
