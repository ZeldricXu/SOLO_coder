package com.schedulebook.repository;

import com.schedulebook.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {
    
    Optional<Resource> findByResourceId(String resourceId);
    
    List<Resource> findByResourceType(String resourceType);
    
    List<Resource> findByResourceTypeAndResourceStatus(String resourceType, String resourceStatus);
    
    @Query("SELECT r FROM Resource r WHERE r.resourceType = :resourceType AND r.resourceStatus = 'available' ORDER BY r.priority DESC, r.currentOccupancy ASC")
    List<Resource> findAvailableResourcesByType(@Param("resourceType") String resourceType);
    
    @Query("SELECT COUNT(r) FROM Resource r WHERE r.resourceType = :resourceType")
    Long countByResourceType(@Param("resourceType") String resourceType);
    
    boolean existsByResourceId(String resourceId);
}
