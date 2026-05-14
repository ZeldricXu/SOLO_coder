package com.configcenter.version.repository;

import com.configcenter.common.entity.ConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigVersionRepository extends JpaRepository<ConfigVersion, String>, JpaSpecificationExecutor<ConfigVersion> {

    @Query("SELECT v FROM ConfigVersion v WHERE v.configId = :configId ORDER BY v.changedAt DESC")
    List<ConfigVersion> findByConfigIdOrderByChangedAtDesc(@Param("configId") String configId);

    @Query("SELECT v FROM ConfigVersion v WHERE v.configId = :configId AND v.version = :version")
    Optional<ConfigVersion> findByConfigIdAndVersion(@Param("configId") String configId, @Param("version") String version);

    @Query("SELECT v FROM ConfigVersion v WHERE v.configId = :configId ORDER BY v.changedAt DESC")
    List<ConfigVersion> findLatestVersions(@Param("configId") String configId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT MAX(v.version) FROM ConfigVersion v WHERE v.configId = :configId")
    Optional<String> findMaxVersion(@Param("configId") String configId);

    boolean existsByConfigIdAndVersion(String configId, String version);

    void deleteByConfigId(String configId);
}
