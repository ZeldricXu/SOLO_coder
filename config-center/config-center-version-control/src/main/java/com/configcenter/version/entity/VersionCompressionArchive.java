package com.configcenter.version.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "version_compression_archive", indexes = {
        @Index(name = "idx_config_id", columnList = "config_id"),
        @Index(name = "idx_archive_time", columnList = "archive_time")
})
public class VersionCompressionArchive {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "archive_id", length = 36)
    private String archiveId;

    @Column(name = "config_id", length = 36, nullable = false)
    private String configId;

    @Column(name = "from_version", length = 20, nullable = false)
    private String fromVersion;

    @Column(name = "to_version", length = 20, nullable = false)
    private String toVersion;

    @Column(name = "version_count", nullable = false)
    private Integer versionCount;

    @Lob
    @Column(name = "compressed_data", nullable = false)
    private byte[] compressedData;

    @Column(name = "compression_algorithm", length = 20, nullable = false)
    private String compressionAlgorithm;

    @Column(name = "original_size", nullable = false)
    private Long originalSize;

    @Column(name = "compressed_size", nullable = false)
    private Long compressedSize;

    @Column(name = "compression_ratio", nullable = false)
    private Double compressionRatio;

    @Column(name = "archive_time", nullable = false)
    private LocalDateTime archiveTime;

    @Column(name = "archived_by", length = 100)
    private String archivedBy;

    @Column(name = "checksum", length = 64)
    private String checksum;
    
    @Column(name = "retention_strategy", length = 50)
    private String retentionStrategy;
    
    @Column(name = "trigger_strategy", length = 50)
    private String triggerStrategy;

    @PrePersist
    public void prePersist() {
        if (archiveTime == null) {
            archiveTime = LocalDateTime.now();
        }
    }
}