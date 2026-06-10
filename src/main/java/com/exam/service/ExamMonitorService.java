package com.exam.service;

import com.exam.vo.*;

import java.util.List;

public interface ExamMonitorService {
    ExamMonitorVO getExamMonitorData(Long examId);

    List<AbnormalAlertVO> getAbnormalAlertList(Long examId, Integer type, Integer severity,
                                                int pageNum, int pageSize);

    OnlineStatusVO getOnlineStatus(Long examId);

    SubmitProgressVO getSubmitProgress(Long examId);

    void handleAbnormal(Long abnormalId, String handleRemark, Long handlerId);

    List<RealtimeExamVO> getRealtimeExamList();
}
