package com.reviewsystem.dto;

public class CommentStatsDTO {

    private String contentId;
    private Integer totalComments;
    private Integer publishedComments;
    private Integer rejectedComments;
    private Integer pendingComments;
    private Double avgQuality;
    private Double avgSentiment;
    private Integer positiveCount;
    private Integer negativeCount;
    private Integer reportCount;
    private Integer totalLikes;
    private Integer totalReplies;

    public CommentStatsDTO() {}

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public Integer getTotalComments() {
        return totalComments;
    }

    public void setTotalComments(Integer totalComments) {
        this.totalComments = totalComments;
    }

    public Integer getPublishedComments() {
        return publishedComments;
    }

    public void setPublishedComments(Integer publishedComments) {
        this.publishedComments = publishedComments;
    }

    public Integer getRejectedComments() {
        return rejectedComments;
    }

    public void setRejectedComments(Integer rejectedComments) {
        this.rejectedComments = rejectedComments;
    }

    public Integer getPendingComments() {
        return pendingComments;
    }

    public void setPendingComments(Integer pendingComments) {
        this.pendingComments = pendingComments;
    }

    public Double getAvgQuality() {
        return avgQuality;
    }

    public void setAvgQuality(Double avgQuality) {
        this.avgQuality = avgQuality;
    }

    public Double getAvgSentiment() {
        return avgSentiment;
    }

    public void setAvgSentiment(Double avgSentiment) {
        this.avgSentiment = avgSentiment;
    }

    public Integer getPositiveCount() {
        return positiveCount;
    }

    public void setPositiveCount(Integer positiveCount) {
        this.positiveCount = positiveCount;
    }

    public Integer getNegativeCount() {
        return negativeCount;
    }

    public void setNegativeCount(Integer negativeCount) {
        this.negativeCount = negativeCount;
    }

    public Integer getReportCount() {
        return reportCount;
    }

    public void setReportCount(Integer reportCount) {
        this.reportCount = reportCount;
    }

    public Integer getTotalLikes() {
        return totalLikes;
    }

    public void setTotalLikes(Integer totalLikes) {
        this.totalLikes = totalLikes;
    }

    public Integer getTotalReplies() {
        return totalReplies;
    }

    public void setTotalReplies(Integer totalReplies) {
        this.totalReplies = totalReplies;
    }
}
