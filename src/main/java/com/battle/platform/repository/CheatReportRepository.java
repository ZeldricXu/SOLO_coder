package com.battle.platform.repository;

import com.battle.platform.entity.CheatReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CheatReportRepository extends JpaRepository<CheatReport, Long> {
    List<CheatReport> findByStatus(CheatReport.ReportStatus status);

    List<CheatReport> findByPlayerId(Long playerId);

    List<CheatReport> findByCheatType(CheatReport.CheatType type);
}
