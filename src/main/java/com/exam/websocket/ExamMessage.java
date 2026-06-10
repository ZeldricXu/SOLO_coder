package com.exam.websocket;

import lombok.Data;

@Data
public class ExamMessage {
    private String type;
    private Long examId;
    private Long examRecordId;
    private Long questionId;
    private String answer;
    private Integer screenSwitchCount;
    private Long remainingTime;
    private String warningMessage;
    private Long timestamp;
    private String data;
}
