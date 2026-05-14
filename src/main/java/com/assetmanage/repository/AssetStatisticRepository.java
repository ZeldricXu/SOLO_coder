package com.assetmanage.repository;

import com.assetmanage.entity.AssetStatistic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetStatisticRepository extends JpaRepository<AssetStatistic, String> {

    Optional<AssetStatistic> findByStatDate(LocalDate statDate);

    List<AssetStatistic> findAllByOrderByStatDateDesc();

    @Query("SELECT s FROM AssetStatistic s WHERE s.statDate BETWEEN :start AND :end ORDER BY s.statDate")
    List<AssetStatistic> findByStatDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
