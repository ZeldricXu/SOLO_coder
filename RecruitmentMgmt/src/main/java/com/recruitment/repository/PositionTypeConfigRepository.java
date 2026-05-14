package com.recruitment.repository;

import com.recruitment.model.PositionTypeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionTypeConfigRepository extends JpaRepository<PositionTypeConfig, Long> {

    Optional<PositionTypeConfig> findByTypeCode(String typeCode);

    List<PositionTypeConfig> findByIsEnabledTrueOrderBySortOrderAsc();

    List<PositionTypeConfig> findAllByOrderBySortOrderAsc();

    boolean existsByTypeCode(String typeCode);

    void deleteByTypeCode(String typeCode);
}
