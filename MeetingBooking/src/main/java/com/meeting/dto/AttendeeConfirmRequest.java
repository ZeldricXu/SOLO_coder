package com.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendeeConfirmRequest {
    @NotBlank(message = "会议ID不能为空")
    private String meetingId;

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "参会状态不能为空")
    private String attendeeStatus;

    private String rejectReason;
}
