package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_sort_scores")
public class TaskSortScore {

    @Id
    @Column(name = "score_id", nullable = false, length = 64)
    private String scoreId;

    @Column(name = "task_id", nullable = false, length = 64, unique = true)
    private String taskId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "priority_score", nullable = false)
    private Integer priorityScore;

    @Column(name = "urgency_score", nullable = false)
    private Integer urgencyScore;

    @Column(name = "workload_score", nullable = false)
    private Integer workloadScore;

    @Column(name = "composite_score", nullable = false)
    private Integer compositeScore;

    @Column(name = "priority_weight", nullable = false)
    private Double priorityWeight;

    @Column(name = "urgency_weight", nullable = false)
    private Double urgencyWeight;

    @Column(name = "workload_weight", nullable = false)
    private Double workloadWeight;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TaskSortScore() {
    }

    public String getScoreId() {
        return scoreId;
    }

    public void setScoreId(String scoreId) {
        this.scoreId = scoreId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Integer getPriorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(Integer priorityScore) {
        this.priorityScore = priorityScore;
    }

    public Integer getUrgencyScore() {
        return urgencyScore;
    }

    public void setUrgencyScore(Integer urgencyScore) {
        this.urgencyScore = urgencyScore;
    }

    public Integer getWorkloadScore() {
        return workloadScore;
    }

    public void setWorkloadScore(Integer workloadScore) {
        this.workloadScore = workloadScore;
    }

    public Integer getCompositeScore() {
        return compositeScore;
    }

    public void setCompositeScore(Integer compositeScore) {
        this.compositeScore = compositeScore;
    }

    public Double getPriorityWeight() {
        return priorityWeight;
    }

    public void setPriorityWeight(Double priorityWeight) {
        this.priorityWeight = priorityWeight;
    }

    public Double getUrgencyWeight() {
        return urgencyWeight;
    }

    public void setUrgencyWeight(Double urgencyWeight) {
        this.urgencyWeight = urgencyWeight;
    }

    public Double getWorkloadWeight() {
        return workloadWeight;
    }

    public void setWorkloadWeight(Double workloadWeight) {
        this.workloadWeight = workloadWeight;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
