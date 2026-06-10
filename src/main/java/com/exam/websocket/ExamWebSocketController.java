package com.exam.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Slf4j
@Controller
public class ExamWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    public ExamWebSocketController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/exam/heartbeat")
    public void handleHeartbeat(@Payload ExamMessage message, Principal principal) {
        log.debug("收到心跳: userId={}, examId={}", principal.getName(), message.getExamId());

        ExamMessage response = new ExamMessage();
        response.setType("heartbeat_ack");
        response.setTimestamp(System.currentTimeMillis());
        response.setExamId(message.getExamId());

        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/exam",
                response
        );
    }

    @MessageMapping("/exam/answer")
    public void handleAnswer(@Payload ExamMessage message, Principal principal) {
        log.debug("收到答题: userId={}, examId={}, questionId={}",
                principal.getName(), message.getExamId(), message.getQuestionId());

        ExamMessage response = new ExamMessage();
        response.setType("answer_saved");
        response.setQuestionId(message.getQuestionId());
        response.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/exam",
                response
        );
    }

    @MessageMapping("/exam/screen-switch")
    public void handleScreenSwitch(@Payload ExamMessage message, Principal principal) {
        log.warn("检测到切屏: userId={}, examId={}, count={}",
                principal.getName(), message.getExamId(), message.getScreenSwitchCount());

        ExamMessage response = new ExamMessage();
        response.setType("screen_switch_warning");
        response.setScreenSwitchCount(message.getScreenSwitchCount());
        response.setWarningMessage("请勿切屏，多次切屏将被判定为作弊");
        response.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/exam",
                response
        );
    }

    public void sendExamStart(Long examId, String userId, Long remainingTime) {
        ExamMessage message = new ExamMessage();
        message.setType("exam_start");
        message.setExamId(examId);
        message.setRemainingTime(remainingTime);
        message.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(userId, "/queue/exam", message);
    }

    public void sendExamEnd(Long examId, String userId) {
        ExamMessage message = new ExamMessage();
        message.setType("exam_end");
        message.setExamId(examId);
        message.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(userId, "/queue/exam", message);
    }

    public void sendTimeWarning(Long examId, String userId, Long remainingTime) {
        ExamMessage message = new ExamMessage();
        message.setType("time_warning");
        message.setExamId(examId);
        message.setRemainingTime(remainingTime);
        message.setWarningMessage("考试剩余时间不足，请加快答题速度");
        message.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(userId, "/queue/exam", message);
    }

    public void sendForcedSubmit(Long examId, String userId, String reason) {
        ExamMessage message = new ExamMessage();
        message.setType("forced_submit");
        message.setExamId(examId);
        message.setWarningMessage(reason);
        message.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSendToUser(userId, "/queue/exam", message);
    }
}
