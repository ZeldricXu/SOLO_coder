package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.model.MailHistory;
import com.example.mailservice.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/mail/{mailId}")
    public ApiResponse<List<MailHistory>> getHistoryByMailId(@PathVariable String mailId) {
        return ApiResponse.success(historyService.getHistoryByMailId(mailId));
    }

    @GetMapping("/mail/{mailId}/page")
    public ApiResponse<Page<MailHistory>> getHistoryPage(
            @PathVariable String mailId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(historyService.getHistoryPage(mailId, page, size));
    }

    @GetMapping("/{historyId}")
    public ApiResponse<MailHistory> getHistoryById(@PathVariable String historyId) {
        return historyService.getHistoryById(historyId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "历史记录不存在"));
    }
}
