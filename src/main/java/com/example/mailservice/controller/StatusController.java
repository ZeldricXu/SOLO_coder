package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.model.SendStatus;
import com.example.mailservice.service.StatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/status")
@RequiredArgsConstructor
public class StatusController {

    private final StatusService statusService;

    @GetMapping("/mail/{mailId}")
    public ApiResponse<SendStatus> getStatusByMailId(@PathVariable String mailId) {
        return statusService.getStatusByMailId(mailId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "发送状态不存在"));
    }

    @GetMapping("/{statusId}")
    public ApiResponse<SendStatus> getStatusById(@PathVariable String statusId) {
        return statusService.getStatusByStatusId(statusId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "状态不存在"));
    }

    @GetMapping("/retry")
    public ApiResponse<List<SendStatus>> getFailedForRetry() {
        return ApiResponse.success(statusService.getFailedStatusesForRetry());
    }
}
