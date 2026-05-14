package com.projmanage.repository;

import com.projmanage.model.Progress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProgressRepository extends JpaRepository<Progress, String> {
    Optional<Progress> findByProjectId(String projectId);
}
