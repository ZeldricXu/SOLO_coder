package com.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class MeetingCreateRequest {
    @NotBlank(message = "会议室ID不能为空")
    private String roomId;

    @NotBlank(message = "会议主题不能为空")
    private String meetingTopic;

    private String meetingType;

    @NotNull(message = "会议开始时间不能为空")
    private LocalDateTime meetingStart;

    @NotNull(message = "会议结束时间不能为空")
    private LocalDateTime meetingEnd;

    @NotBlank(message = "发起人ID不能为空")
    private String organizerId;

    private String organizerName;

    private String description;

    private List<AttendeeInfo> attendees;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttendeeInfo {
        private String userId;
        private String userName;
        private String userEmail;
    }
}
