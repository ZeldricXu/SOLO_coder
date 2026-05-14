package com.mobilestore.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "versions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Version {

    @Id
    @Column(name = "version_id", length = 50)
    private String versionId;

    @Column(name = "app_id", length = 50, nullable = false)
    private String appId;

    @Column(name = "version_code", length = 20, nullable = false)
    private String versionCode;

    @Column(name = "version_name", length = 50)
    private String versionName;

    @Column(name = "package_url", length = 500)
    private String packageUrl;

    @Column(name = "release_note", columnDefinition = "TEXT")
    private String releaseNote;

    @Column(name = "publish_status", length = 20)
    private String publishStatus;

    @Column(name = "submitter", length = 50)
    private String submitter;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approver", length = 50)
    private String approver;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @PrePersist
    protected void onCreate() {
        submittedAt = LocalDateTime.now();
    }
}
