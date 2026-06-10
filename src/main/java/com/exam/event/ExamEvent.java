package com.exam.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ExamEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventType;
    private Long examId;
    private Long sessionId;
    private Long studentId;
    private Long questionId;
    private String answer;
    private LocalDateTime eventTime;
    private String clientIp;
    private Map<String, Object> extra = new HashMap<>();

    public ExamEvent() {
        this.eventTime = LocalDateTime.now();
    }

    public ExamEvent(String eventType) {
        this();
        this.eventType = eventType;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getExamId() {
        return examId;
    }

    public void setExamId(Long examId) {
        this.examId = examId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public void addExtra(String key, Object value) {
        this.extra.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getExtra(String key) {
        return (T) extra.get(key);
    }
}
