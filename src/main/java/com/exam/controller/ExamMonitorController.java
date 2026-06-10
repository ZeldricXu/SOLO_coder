package com.exam.controller;

import com.exam.common.Result;
import com.exam.service.ExamMonitorService;
import com.exam.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teacher/monitor")
@RequiredArgsConstructor
public class ExamMonitorController {

    private final ExamMonitorService examMonitorService;

    @GetMapping("/exam/{examId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<ExamMonitorVO> getExamMonitorData(@PathVariable Long examId) {
        return Result.success(examMonitorService.getExamMonitorData(examId));
    }

    @GetMapping("/abnormals")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<List<AbnormalAlertVO>> getAbnormalAlertList(
            @RequestParam(required = false) Long examId,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Integer severity,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(examMonitorService.getAbnormalAlertList(examId, type, severity, pageNum, pageSize));
    }

    @GetMapping("/online/{examId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<OnlineStatusVO> getOnlineStatus(@PathVariable Long examId) {
        return Result.success(examMonitorService.getOnlineStatus(examId));
    }

    @GetMapping("/submit/{examId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<SubmitProgressVO> getSubmitProgress(@PathVariable Long examId) {
        return Result.success(examMonitorService.getSubmitProgress(examId));
    }

    @PostMapping("/abnormal/{id}/handle")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> handleAbnormal(@PathVariable Long id,
                                       @RequestParam String remark,
                                       HttpServletRequest request) {
        Long handlerId = (Long) request.getAttribute("currentUserId");
        examMonitorService.handleAbnormal(id, remark, handlerId);
        return Result.success();
    }

    @GetMapping("/realtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<List<RealtimeExamVO>> getRealtimeExamList() {
        return Result.success(examMonitorService.getRealtimeExamList());
    }
}
