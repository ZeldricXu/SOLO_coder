package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.HistoryType;
import com.recruitment.history.HistoryService;
import com.recruitment.model.History;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {
    private final HistoryService historyService;

    @GetMapping("/resume/{resumeId}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByResume(@PathVariable String resumeId) {
        List<History> histories = historyService.getHistoryByResume(resumeId);
        return ResponseEntity.ok(ApiResponse.success(histories));
    }

    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByCandidate(@PathVariable String candidateId) {
        List<History> histories = historyService.getHistoryByCandidate(candidateId);
        return ResponseEntity.ok(ApiResponse.success(histories));
    }

    @GetMapping("/position/{positionId}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByPosition(@PathVariable String positionId) {
        List<History> histories = historyService.getHistoryByPosition(positionId);
        return ResponseEntity.ok(ApiResponse.success(histories));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByType(@PathVariable HistoryType type) {
        List<History> histories = historyService.getHistoryByType(type);
        return ResponseEntity.ok(ApiResponse.success(histories));
    }
}
