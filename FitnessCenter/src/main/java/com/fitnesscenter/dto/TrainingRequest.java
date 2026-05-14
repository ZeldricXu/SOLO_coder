package com.fitnesscenter.dto;

public class TrainingRequest {

    private String memberId;
    private String courseId;
    private Integer trainingDuration;
    private String trainingIntensity;

    public TrainingRequest() {}

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public Integer getTrainingDuration() {
        return trainingDuration;
    }

    public void setTrainingDuration(Integer trainingDuration) {
        this.trainingDuration = trainingDuration;
    }

    public String getTrainingIntensity() {
        return trainingIntensity;
    }

    public void setTrainingIntensity(String trainingIntensity) {
        this.trainingIntensity = trainingIntensity;
    }
}
