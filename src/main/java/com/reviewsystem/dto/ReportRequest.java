package com.reviewsystem.dto;

import javax.validation.constraints.NotBlank;

public class ReportRequest {

    @NotBlank(message = "评论ID不能为空")
    private String commentId;

    @NotBlank(message = "举报类型不能为空")
    private String reportType;

    @NotBlank(message = "举报原因不能为空")
    private String reportReason;

    private String reportUserId;

    public ReportRequest() {}

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public String getReportReason() {
        return reportReason;
    }

    public void setReportReason(String reportReason) {
        this.reportReason = reportReason;
    }

    public String getReportUserId() {
        return reportUserId;
    }

    public void setReportUserId(String reportUserId) {
        this.reportUserId = reportUserId;
    }
}
