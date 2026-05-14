package com.configcenter.group.repository;

import com.configcenter.common.entity.ApplicationInstance;
import com.configcenter.common.enums.InstanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationInstanceRepository extends JpaRepository<ApplicationInstance, String>, JpaSpecificationExecutor<ApplicationInstance> {

    Optional<ApplicationInstance> findByInstanceAddress(String instanceAddress);

    List<ApplicationInstance> findByApplication(String application);

    @Query("SELECT i FROM ApplicationInstance i WHERE i.application = :application AND i.status = :status")
    List<ApplicationInstance> findByApplicationAndStatus(
            @Param("application") String application,
            @Param("status") InstanceStatus status);

    @Query("SELECT i FROM ApplicationInstance i WHERE i.application IN :applications AND i.status = :status")
    List<ApplicationInstance> findByApplicationsAndStatus(
            @Param("applications") List<String> applications,
            @Param("status") InstanceStatus status);

    @Query("SELECT i FROM ApplicationInstance i WHERE i.status = :status")
    List<ApplicationInstance> findByStatus(@Param("status") InstanceStatus status);

    boolean existsByInstanceAddress(String instanceAddress);
}
