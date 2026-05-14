package com.mobilestore.repository;

import com.mobilestore.entity.App;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppRepository extends JpaRepository<App, String> {

    Optional<App> findByAppId(String appId);

    List<App> findByDeveloperId(String developerId);

    List<App> findByStatus(String status);

    List<App> findByDeveloperIdAndStatus(String developerId, String status);

    boolean existsByNameAndPlatform(String name, String platform);
}
