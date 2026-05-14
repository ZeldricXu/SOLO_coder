package com.memberscore.controller;

import com.memberscore.dto.ApiResponse;
import com.memberscore.entity.PointRecord;
import com.memberscore.service.HistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
@Slf4j
public class HistoryController {
    
    private final HistoryService historyService;
    
    @GetMapping("/{memberId}")
    public ResponseEntity<ApiResponse<List<PointRecord>>> getMemberHistory(
            @PathVariable String memberId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        try {
            List<PointRecord> records;
            
            if (startDate != null && endDate != null) {
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                LocalDateTime start = LocalDateTime.parse(startDate, formatter);
                LocalDateTime end = LocalDateTime.parse(endDate, formatter);
                records = historyService.getMemberHistoryByDateRange(memberId, start, end);
            } else if ("earn".equalsIgnoreCase(type)) {
                records = historyService.getMemberEarnHistory(memberId);
            } else if ("consume".equalsIgnoreCase(type)) {
                records = historyService.getMemberConsumeHistory(memberId);
            } else {
                records = historyService.getMemberRecentHistory(memberId, limit);
            }
            
            return ResponseEntity.ok(ApiResponse.success(records));
        } catch (Exception e) {
            log.error("查询积分历史失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
        }
    }
}
