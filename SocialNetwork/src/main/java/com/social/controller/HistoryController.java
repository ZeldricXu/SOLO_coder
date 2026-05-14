package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.HistoryRecord;
import com.social.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/{userId}")
    public ApiResponse<List<HistoryRecord>> getUserHistory(@PathVariable String userId) {
        List<HistoryRecord> history = historyService.getUserHistory(userId);
        return ApiResponse.success(history);
    }

    @GetMapping("/{userId}/type/{recordType}")
    public ApiResponse<List<HistoryRecord>> getUserHistoryByType(@PathVariable String userId, @PathVariable String recordType) {
        List<HistoryRecord> history = historyService.getUserHistoryByType(userId, recordType);
        return ApiResponse.success(history);
    }

    @PostMapping("/record")
    public ApiResponse<HistoryRecord> recordHistory(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        String recordType = (String) request.get("record_type");
        String targetId = (String) request.get("target_id");
        String content = (String) request.get("record_content");

        if (userId == null || recordType == null) {
            return ApiResponse.error(400, "用户ID和记录类型不能为空");
        }

        HistoryRecord record = historyService.recordHistory(userId, recordType, targetId, content);
        return ApiResponse.success(record);
    }
}
