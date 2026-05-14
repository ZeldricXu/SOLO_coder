package com.projectcollab.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "project_statistics")
public class ProjectStatistics {

    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_month", nullable = false)
    private String statMonth;

    @Column(name = "project_count")
    private int projectCount;

    @Column(name = "task_count")
    private int taskCount;

    @Column(name = "completed_task_count")
    private int completedTaskCount;

    @Column(name = "avg_completion_time")
    private double avgCompletionTime;

    @Column(name = "document_count")
    private int documentCount;

    @Column(name = "member_count")
    private int memberCount;

    public ProjectStatistics() {
    }

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public int getProjectCount() {
        return projectCount;
    }

    public void setProjectCount(int projectCount) {
        this.projectCount = projectCount;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(int taskCount) {
        this.taskCount = taskCount;
    }

    public int getCompletedTaskCount() {
        return completedTaskCount;
    }

    public void setCompletedTaskCount(int completedTaskCount) {
        this.completedTaskCount = completedTaskCount;
    }

    public double getAvgCompletionTime() {
        return avgCompletionTime;
    }

    public void setAvgCompletionTime(double avgCompletionTime) {
        this.avgCompletionTime = avgCompletionTime;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public void setDocumentCount(int documentCount) {
        this.documentCount = documentCount;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(int memberCount) {
        this.memberCount = memberCount;
    }
}
