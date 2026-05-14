package com.logistics.repository;

import com.logistics.entity.LogisticsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LogisticsHistoryRepository extends JpaRepository<LogisticsHistory, String> {

    List<LogisticsHistory> findByLogisticsIdOrderByHistoryTimeAsc(String logisticsId);

    List<LogisticsHistory> findByLogisticsId(String logisticsId);

    List<LogisticsHistory> findByHistoryType(String historyType);
}
