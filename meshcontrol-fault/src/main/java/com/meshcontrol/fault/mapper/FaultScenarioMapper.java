package com.meshcontrol.fault.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.fault.entity.FaultScenario;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FaultScenarioMapper extends BaseMapper<FaultScenario> {

    @Select("SELECT * FROM fault_scenario WHERE fault_type = #{faultType} AND deleted = 0")
    List<FaultScenario> findByType(@Param("faultType") String faultType);

    @Select("SELECT * FROM fault_scenario WHERE enabled = 1 AND deleted = 0")
    List<FaultScenario> findActive();
}
