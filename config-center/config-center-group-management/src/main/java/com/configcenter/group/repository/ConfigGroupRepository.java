package com.configcenter.group.repository;

import com.configcenter.common.entity.ConfigGroup;
import com.configcenter.common.enums.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigGroupRepository extends JpaRepository<ConfigGroup, String>, JpaSpecificationExecutor<ConfigGroup> {

    Optional<ConfigGroup> findByGroupIdAndDeletedFalse(String groupId);

    @Query("SELECT g FROM ConfigGroup g WHERE g.groupName = :groupName AND g.environment = :environment AND g.deleted = false")
    Optional<ConfigGroup> findByNameAndEnvironment(@Param("groupName") String groupName, @Param("environment") Environment environment);

    @Query("SELECT g FROM ConfigGroup g WHERE g.environment = :environment AND g.deleted = false")
    List<ConfigGroup> findByEnvironmentAndDeletedFalse(@Param("environment") Environment environment);

    @Query("SELECT g FROM ConfigGroup g WHERE g.deleted = false")
    List<ConfigGroup> findAllActive();

    @Query("SELECT g FROM ConfigGroup g WHERE :application MEMBER OF g.applications AND g.environment = :environment AND g.deleted = false")
    List<ConfigGroup> findByApplicationAndEnvironment(
            @Param("application") String application,
            @Param("environment") Environment environment);

    boolean existsByGroupNameAndEnvironmentAndDeletedFalse(String groupName, Environment environment);
}
