package com.exam.event.listener;

import com.exam.entity.ExamAbnormal;
import com.exam.entity.ExamSession;
import com.exam.event.ExamEvent;
import com.exam.event.ExamEventListener;
import com.exam.event.ExamEventType;
import com.exam.mapper.ExamAbnormalMapper;
import com.exam.mapper.ExamSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AbnormalRecordListener implements ExamEventListener {

    private final ExamAbnormalMapper examAbnormalMapper;
    private final ExamSessionMapper examSessionMapper;

    @Override
    public String getName() {
        return "ABNORMAL_RECORD_LISTENER";
    }

    @Override
    public boolean supports(String eventType) {
        return ExamEventType.SCREEN_SWITCH.equals(eventType)
                || ExamEventType.ABNORMAL.equals(eventType)
                || ExamEventType.SESSION_DISCONNECT.equals(eventType);
    }

    @Override
    public void onEvent(ExamEvent event) {
        Long examId = event.getExamId();
        Long sessionId = event.getSessionId();
        Long studentId = event.getStudentId();

        if (examId == null) {
            return;
        }

        try {
            if (ExamEventType.SCREEN_SWITCH.equals(event.getEventType())) {
                handleScreenSwitch(event, examId, sessionId, studentId);
            } else if (ExamEventType.SESSION_DISCONNECT.equals(event.getEventType())) {
                handleDisconnect(event, examId, sessionId, studentId);
            } else {
                recordAbnormal(examId, sessionId, studentId,
                        event.getExtra("abnormalType") != null ? (Integer) event.getExtra("abnormalType") : 0,
                        event.getExtra("description") != null ? (String) event.getExtra("description") : "异常行为",
                        event.getClientIp());
            }
        } catch (Exception e) {
            log.error("记录异常行为失败: eventType={}, examId={}", event.getEventType(), examId, e);
        }
    }

    private void handleScreenSwitch(ExamEvent event, Long examId, Long sessionId, Long studentId) {
        if (sessionId != null) {
            ExamSession session = examSessionMapper.selectById(sessionId);
            if (session != null) {
                int count = (session.getScreenSwitchCount() == null ? 0 : session.getScreenSwitchCount()) + 1;
                session.setScreenSwitchCount(count);
                session.setAbnormalCount((session.getAbnormalCount() == null ? 0 : session.getAbnormalCount()) + 1);
                examSessionMapper.updateById(session);
            }
        }

        Integer switchCount = event.getExtra("screenSwitchCount");
        String desc = String.format("第%d次检测到切屏行为", switchCount != null ? switchCount : 1);
        recordAbnormal(examId, sessionId, studentId, 1, desc, event.getClientIp());
    }

    private void handleDisconnect(ExamEvent event, Long examId, Long sessionId, Long studentId) {
        if (sessionId != null) {
            ExamSession session = examSessionMapper.selectById(sessionId);
            if (session != null) {
                session.setReconnectCount((session.getReconnectCount() == null ? 0 : session.getReconnectCount()) + 1);
                examSessionMapper.updateById(session);
            }
        }
        recordAbnormal(examId, sessionId, studentId, 2, "会话断开连接", event.getClientIp());
    }

    private void recordAbnormal(Long examId, Long sessionId, Long studentId,
                                 Integer abnormalType, String description, String ip) {
        ExamAbnormal abnormal = new ExamAbnormal();
        abnormal.setExamId(examId);
        abnormal.setSessionId(sessionId);
        abnormal.setStudentId(studentId);
        abnormal.setAbnormalType(abnormalType);
        abnormal.setAbnormalName(getTypeName(abnormalType));
        abnormal.setDescription(description);
        abnormal.setHappenTime(LocalDateTime.now());
        abnormal.setClientIp(ip);
        abnormal.setHandled(0);
        examAbnormalMapper.insert(abnormal);

        log.info("记录考试异常: examId={}, studentId={}, type={}, desc={}",
                examId, studentId, abnormalType, description);
    }

    private String getTypeName(Integer type) {
        return switch (type) {
            case 1 -> "切屏";
            case 2 -> "断线";
            case 3 -> "失焦";
            case 4 -> "复制粘贴";
            default -> "其他异常";
        };
    }

    @Override
    public int getOrder() {
        return 80;
    }
}
