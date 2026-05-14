package com.homeservice.repository;

import com.homeservice.entity.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceTypeRepository extends JpaRepository<ServiceType, Long> {
    Optional<ServiceType> findByTypeCode(String typeCode);
    List<ServiceType> findByIsActiveTrue();
    boolean existsByTypeCode(String typeCode);
    
    @Query("SELECT s FROM ServiceType s WHERE s.isActive = true AND (s.supportedRegions LIKE %:region% OR s.supportedRegions IS NULL)")
    List<ServiceType> findBySupportedRegionsContaining(@Param("region") String region);
}
