package com.cms.repository;

import com.cms.entity.ContentTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentTypeConfigRepository extends JpaRepository<ContentTypeConfig, String> {

    Optional<ContentTypeConfig> findByTypeCode(String typeCode);

    Optional<ContentTypeConfig> findByTypeCodeAndIsActiveTrue(String typeCode);

    List<ContentTypeConfig> findByIsActiveTrueOrderBySortOrderAsc();

    List<ContentTypeConfig> findAllByOrderBySortOrderAsc();

    List<ContentTypeConfig> findByDefaultCategory(String defaultCategory);

    boolean existsByTypeCode(String typeCode);

    @Query("SELECT c.typeCode FROM ContentTypeConfig c WHERE c.isActive = true")
    List<String> findActiveTypeCodes();

    @Query("SELECT c FROM ContentTypeConfig c WHERE c.defaultUrgencyLevel = :urgencyLevel AND c.isActive = true")
    List<ContentTypeConfig> findByDefaultUrgencyLevel(@Param("urgencyLevel") String urgencyLevel);

    @Query("SELECT c FROM ContentTypeConfig c WHERE c.defaultImportanceLevel = :importanceLevel AND c.isActive = true")
    List<ContentTypeConfig> findByDefaultImportanceLevel(@Param("importanceLevel") String importanceLevel);

    void deleteByTypeCode(String typeCode);
}
