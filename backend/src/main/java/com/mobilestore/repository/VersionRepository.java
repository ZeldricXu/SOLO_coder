package com.mobilestore.repository;

import com.mobilestore.entity.Version;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VersionRepository extends JpaRepository<Version, String> {

    Optional<Version> findByVersionId(String versionId);

    List<Version> findByAppIdOrderBySubmittedAtDesc(String appId);

    List<Version> findByPublishStatusOrderBySubmittedAtDesc(String publishStatus);

    List<Version> findByAppIdAndPublishStatusOrderBySubmittedAtDesc(String appId, String publishStatus);

    boolean existsByAppIdAndVersionCode(String appId, String versionCode);

    Optional<Version> findFirstByAppIdAndPublishStatusOrderByApprovedAtDesc(String appId, String publishStatus);
}
