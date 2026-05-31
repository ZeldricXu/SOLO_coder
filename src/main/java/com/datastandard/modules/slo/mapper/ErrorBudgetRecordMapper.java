package com.datastandard.modules.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.slo.entity.ErrorBudgetRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ErrorBudgetRecordMapper extends BaseMapper<ErrorBudgetRecord> {

    @Select("SELECT * FROM error_budget_records WHERE record_id = #{recordId} AND deleted = 0")
    Optional<ErrorBudgetRecord> findById(@Param("recordId") String recordId);

    @Select("SELECT * FROM error_budget_records WHERE slo_id = #{sloId} AND deleted = 0 ORDER BY created_at DESC LIMIT #{limit}")
    List<ErrorBudgetRecord> findBySloId(@Param("sloId") String sloId, @Param("limit") int limit);

    @Select("SELECT * FROM error_budget_records WHERE slo_id = #{sloId} " +
            "AND window_start >= #{startTime} AND window_end < #{endTime} AND deleted = 0 " +
            "ORDER BY window_start ASC")
    List<ErrorBudgetRecord> findBySloIdAndTimeRange(
            @Param("sloId") String sloId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT * FROM error_budget_records WHERE slo_id = #{sloId} AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    Optional<ErrorBudgetRecord> findLatestBySloId(@Param("sloId") String sloId);
}
