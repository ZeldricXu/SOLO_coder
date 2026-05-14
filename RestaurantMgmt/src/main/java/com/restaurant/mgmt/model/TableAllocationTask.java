package com.restaurant.mgmt.model;

import java.time.LocalDateTime;

public class TableAllocationTask {

    private String taskId;
    private String orderId;
    private String tableNumber;
    private String tableId;
    private int guestCount;
    private String customerId;
    private String reservedBy;
    private LocalDateTime reserveTime;
    private String taskType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
    private String errorMessage;
    private int retryCount;

    public TableAllocationTask() {
        this.createdAt = LocalDateTime.now();
        this.status = "pending";
        this.retryCount = 0;
    }

    public static TableAllocationTask createReserveTask(String tableNumber, LocalDateTime reserveTime, String reservedBy) {
        TableAllocationTask task = new TableAllocationTask();
        task.setTableNumber(tableNumber);
        task.setReserveTime(reserveTime);
        task.setReservedBy(reservedBy);
        task.setTaskType("reserve");
        return task;
    }

    public static TableAllocationTask createAllocateTask(int guestCount, String orderId, String customerId) {
        TableAllocationTask task = new TableAllocationTask();
        task.setGuestCount(guestCount);
        task.setOrderId(orderId);
        task.setCustomerId(customerId);
        task.setTaskType("allocate");
        return task;
    }

    public static TableAllocationTask createOccupyTask(String tableId, String orderId) {
        TableAllocationTask task = new TableAllocationTask();
        task.setTableId(tableId);
        task.setOrderId(orderId);
        task.setTaskType("occupy");
        return task;
    }

    public static TableAllocationTask createReleaseTask(String tableId, String orderId) {
        TableAllocationTask task = new TableAllocationTask();
        task.setTableId(tableId);
        task.setOrderId(orderId);
        task.setTaskType("release");
        return task;
    }

    public static TableAllocationTask createCancelTask(String tableId, String reason) {
        TableAllocationTask task = new TableAllocationTask();
        task.setTableId(tableId);
        task.setTaskType("cancel");
        task.setErrorMessage(reason);
        return task;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getTableNumber() {
        return tableNumber;
    }

    public void setTableNumber(String tableNumber) {
        this.tableNumber = tableNumber;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public int getGuestCount() {
        return guestCount;
    }

    public void setGuestCount(int guestCount) {
        this.guestCount = guestCount;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getReservedBy() {
        return reservedBy;
    }

    public void setReservedBy(String reservedBy) {
        this.reservedBy = reservedBy;
    }

    public LocalDateTime getReserveTime() {
        return reserveTime;
    }

    public void setReserveTime(LocalDateTime reserveTime) {
        this.reserveTime = reserveTime;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }
}
