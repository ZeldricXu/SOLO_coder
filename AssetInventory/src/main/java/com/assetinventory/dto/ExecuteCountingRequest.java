package com.assetinventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;

public class ExecuteCountingRequest {

    @NotBlank(message = "task_id不能为空")
    private String taskId;

    @NotBlank(message = "asset_id不能为空")
    private String assetId;

    @Min(value = 0, message = "count_quantity不能为负数")
    private int countQuantity;

    private String countLocation;

    public ExecuteCountingRequest() {
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public int getCountQuantity() {
        return countQuantity;
    }

    public void setCountQuantity(int countQuantity) {
        this.countQuantity = countQuantity;
    }

    public String getCountLocation() {
        return countLocation;
    }

    public void setCountLocation(String countLocation) {
        this.countLocation = countLocation;
    }
}
