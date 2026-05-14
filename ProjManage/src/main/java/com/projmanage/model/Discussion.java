package com.projmanage.model;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "discussions")
public class Discussion {

    @Id
    @Column(name = "discussion_id", nullable = false, length = 64)
    private String discussionId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "discussion_type", nullable = false, length = 32)
    private String discussionType;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "author", nullable = false, length = 64)
    private String author;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Discussion() {
    }

    public String getDiscussionId() {
        return discussionId;
    }

    public void setDiscussionId(String discussionId) {
        this.discussionId = discussionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getDiscussionType() {
        return discussionType;
    }

    public void setDiscussionType(String discussionType) {
        this.discussionType = discussionType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
