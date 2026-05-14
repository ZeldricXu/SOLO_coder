package com.projmanage.repository;

import com.projmanage.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByProjectId(String projectId);
    List<Report> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
