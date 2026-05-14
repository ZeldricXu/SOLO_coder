package com.flightmgmt.common.model;

import java.time.LocalDateTime;

public class NotificationTask {
    private String taskId;
    private String bookingId;
    private String passengerId;
    private String flightId;
    private String notificationType;
    private String title;
    private String content;
    private int retryCount;
    private int maxRetries;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime confirmedAt;
    private String passengerPhone;
    private String passengerEmail;

    public NotificationTask() {}

    public static NotificationTask create(String bookingId, String passengerId, String flightId,
                                           String notificationType, String title, String content,
                                           int maxRetries) {
        NotificationTask task = new NotificationTask();
        task.setTaskId("notif_task_" + System.currentTimeMillis() + "_" + 
            java.util.UUID.randomUUID().toString().substring(0, 8));
        task.setBookingId(bookingId);
        task.setPassengerId(passengerId);
        task.setFlightId(flightId);
        task.setNotificationType(notificationType);
        task.setTitle(title);
        task.setContent(content);
        task.setRetryCount(0);
        task.setMaxRetries(maxRetries);
        task.setStatus("pending");
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    public boolean canRetry() {
        return "pending".equals(status) || "failed".equals(status) && retryCount < maxRetries;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public boolean isMaxRetriesReached() {
        return retryCount >= maxRetries;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getPassengerId() { return passengerId; }
    public void setPassengerId(String passengerId) { this.passengerId = passengerId; }
    public String getFlightId() { return flightId; }
    public void setFlightId(String flightId) { this.flightId = flightId; }
    public String getNotificationType() { return notificationType; }
    public void setNotificationType(String notificationType) { this.notificationType = notificationType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getPassengerPhone() { return passengerPhone; }
    public void setPassengerPhone(String passengerPhone) { this.passengerPhone = passengerPhone; }
    public String getPassengerEmail() { return passengerEmail; }
    public void setPassengerEmail(String passengerEmail) { this.passengerEmail = passengerEmail; }
}
