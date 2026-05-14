package com.assetinventory.dto;

import jakarta.validation.constraints.NotBlank;

public class ProcessDifferenceRequest {

    @NotBlank(message = "diff_id不能为空")
    private String diffId;

    @NotBlank(message = "diff_status不能为空")
    private String diffStatus;

    public ProcessDifferenceRequest() {
    }

    public String getDiffId() {
        return diffId;
    }

    public void setDiffId(String diffId) {
        this.diffId = diffId;
    }

    public String getDiffStatus() {
        return diffStatus;
    }

    public void setDiffStatus(String diffStatus) {
        this.diffStatus = diffStatus;
    }
}
