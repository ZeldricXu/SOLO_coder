package com.configcenter.version.repository;

import com.configcenter.version.entity.VersionCompressionArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VersionCompressionArchiveRepository extends JpaRepository<VersionCompressionArchive, String>, 
        JpaSpecificationExecutor<VersionCompressionArchive> {

    List<VersionCompressionArchive> findByConfigIdOrderByArchiveTimeDesc(String configId);

    @Query("SELECT a FROM VersionCompressionArchive a WHERE a.configId = :configId AND a.fromVersion <= :version AND a.toVersion >= :version ORDER BY a.archiveTime DESC")
    List<VersionCompressionArchive> findByConfigIdAndVersionInRange(
            @Param("configId") String configId,
            @Param("version") String version);

    Optional<VersionCompressionArchive> findFirstByConfigIdOrderByArchiveTimeDesc(String configId);

    @Query("SELECT COUNT(a) FROM VersionCompressionArchive a WHERE a.configId = :configId")
    Long countByConfigId(@Param("configId") String configId);

    @Query("SELECT a FROM VersionCompressionArchive a WHERE a.archiveTime < :cutoffTime")
    List<VersionCompressionArchive> findArchivesOlderThan(@Param("cutoffTime") LocalDateTime cutoffTime);
}
