package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "milestones")
public class Milestone {

    @Id
    @Column(name = "milestone_id", nullable = false, length = 64)
    private String milestoneId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "milestone_name", nullable = false, length = 255)
    private String milestoneName;

    @Column(name = "milestone_date")
    private LocalDate milestoneDate;

    @ElementCollection
    @CollectionTable(name = "milestone_tasks", joinColumns = @JoinColumn(name = "milestone_id"))
    @Column(name = "task_id")
    private List<String> milestoneTasks = new ArrayList<>();

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "progress")
    private Integer progress;

    public Milestone() {
    }

    public String getMilestoneId() {
        return milestoneId;
    }

    public void setMilestoneId(String milestoneId) {
        this.milestoneId = milestoneId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getMilestoneName() {
        return milestoneName;
    }

    public void setMilestoneName(String milestoneName) {
        this.milestoneName = milestoneName;
    }

    public LocalDate getMilestoneDate() {
        return milestoneDate;
    }

    public void setMilestoneDate(LocalDate milestoneDate) {
        this.milestoneDate = milestoneDate;
    }

    public List<String> getMilestoneTasks() {
        return milestoneTasks;
    }

    public void setMilestoneTasks(List<String> milestoneTasks) {
        this.milestoneTasks = milestoneTasks;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }
}
