package com.fitnesscenter.dto;

public class TrainingResponse {

    private String trainingId;
    private Integer calories;

    public TrainingResponse() {}

    public TrainingResponse(String trainingId, Integer calories) {
        this.trainingId = trainingId;
        this.calories = calories;
    }

    public String getTrainingId() {
        return trainingId;
    }

    public void setTrainingId(String trainingId) {
        this.trainingId = trainingId;
    }

    public Integer getCalories() {
        return calories;
    }

    public void setCalories(Integer calories) {
        this.calories = calories;
    }
}
