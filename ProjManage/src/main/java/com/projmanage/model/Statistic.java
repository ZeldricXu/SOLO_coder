package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "statistics")
public class Statistic {

    @Id
    @Column(name = "stat_id", nullable = false, length = 64)
    private String statId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "total_hours")
    private Integer totalHours;

    @Column(name = "completed_hours")
    private Integer completedHours;

    @Column(name = "task_completion_rate")
    private Integer taskCompletionRate;

    @Column(name = "on_time_rate")
    private Integer onTimeRate;

    public Statistic() {
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Integer getTotalHours() {
        return totalHours;
    }

    public void setTotalHours(Integer totalHours) {
        this.totalHours = totalHours;
    }

    public Integer getCompletedHours() {
        return completedHours;
    }

    public void setCompletedHours(Integer completedHours) {
        this.completedHours = completedHours;
    }

    public Integer getTaskCompletionRate() {
        return taskCompletionRate;
    }

    public void setTaskCompletionRate(Integer taskCompletionRate) {
        this.taskCompletionRate = taskCompletionRate;
    }

    public Integer getOnTimeRate() {
        return onTimeRate;
    }

    public void setOnTimeRate(Integer onTimeRate) {
        this.onTimeRate = onTimeRate;
    }
}
