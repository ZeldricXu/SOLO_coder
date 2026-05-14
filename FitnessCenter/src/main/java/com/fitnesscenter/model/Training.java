package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "trainings")
public class Training {

    @Id
    @Column(name = "training_id")
    private String trainingId;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "course_id", nullable = false)
    private String courseId;

    @Column(name = "training_duration")
    private Integer trainingDuration;

    @Column(name = "training_intensity")
    private String trainingIntensity;

    @Column(name = "training_calories")
    private Integer trainingCalories;

    @Column(name = "training_time")
    private Instant trainingTime;

    @Column(name = "training_effect_score")
    private Double trainingEffectScore;

    public Training() {}

    public String getTrainingId() {
        return trainingId;
    }

    public void setTrainingId(String trainingId) {
        this.trainingId = trainingId;
    }

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

    public Integer getTrainingCalories() {
        return trainingCalories;
    }

    public void setTrainingCalories(Integer trainingCalories) {
        this.trainingCalories = trainingCalories;
    }

    public Instant getTrainingTime() {
        return trainingTime;
    }

    public void setTrainingTime(Instant trainingTime) {
        this.trainingTime = trainingTime;
    }

    public Double getTrainingEffectScore() {
        return trainingEffectScore;
    }

    public void setTrainingEffectScore(Double trainingEffectScore) {
        this.trainingEffectScore = trainingEffectScore;
    }
}
