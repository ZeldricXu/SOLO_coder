package com.projmanage.repository;

import com.projmanage.model.Statistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StatisticRepository extends JpaRepository<Statistic, String> {
    List<Statistic> findByProjectId(String projectId);
    List<Statistic> findByProjectIdOrderByStatDateDesc(String projectId);
}
