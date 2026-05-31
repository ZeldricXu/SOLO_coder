package com.meshcontrol.fault.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.fault.entity.FaultInjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FaultInjectionMapper extends BaseMapper<FaultInjection> {

    @Select("SELECT * FROM fault_injection WHERE scenario_id = #{scenarioId} ORDER BY created_at DESC")
    List<FaultInjection> findByScenarioId(@Param("scenarioId") String scenarioId);

    @Select("SELECT * FROM fault_injection WHERE status = #{status}")
    List<FaultInjection> findByStatus(@Param("status") String status);
}
