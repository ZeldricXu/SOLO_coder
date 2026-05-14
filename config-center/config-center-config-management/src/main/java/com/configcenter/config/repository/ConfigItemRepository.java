package com.configcenter.config.repository;

import com.configcenter.common.entity.ConfigItem;
import com.configcenter.common.enums.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConfigItemRepository extends JpaRepository<ConfigItem, String>, JpaSpecificationExecutor<ConfigItem> {

    Optional<ConfigItem> findByConfigIdAndDeletedFalse(String configId);

    @Query("SELECT c FROM ConfigItem c WHERE c.configKey = :configKey AND c.environment = :environment AND c.groupId = :groupId AND c.deleted = false")
    Optional<ConfigItem> findByConfigKeyAndEnvironmentAndGroupId(
            @Param("configKey") String configKey,
            @Param("environment") Environment environment,
            @Param("groupId") String groupId);

    @Query("SELECT c FROM ConfigItem c WHERE c.groupId = :groupId AND c.deleted = false")
    List<ConfigItem> findByGroupIdAndDeletedFalse(@Param("groupId") String groupId);

    @Query("SELECT c FROM ConfigItem c WHERE c.groupId = :groupId AND c.environment = :environment AND c.deleted = false")
    List<ConfigItem> findByGroupIdAndEnvironmentAndDeletedFalse(
            @Param("groupId") String groupId,
            @Param("environment") Environment environment);

    @Query("SELECT c FROM ConfigItem c WHERE c.environment = :environment AND c.deleted = false")
    List<ConfigItem> findByEnvironmentAndDeletedFalse(@Param("environment") Environment environment);

    boolean existsByConfigKeyAndEnvironmentAndGroupIdAndDeletedFalse(
            String configKey, Environment environment, String groupId);
}
