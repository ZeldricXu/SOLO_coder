package com.cicd.server.repository;

import com.cicd.server.entity.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnvironmentRepository extends JpaRepository<Environment, Long> {

    Optional<Environment> findByName(String name);

    List<Environment> findByIsActiveTrue();

    List<Environment> findByRequiresApprovalTrue();
}
