package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.EmployeeSkillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmployeeSkillMapper extends BaseMapper<EmployeeSkillEntity> {

    @Select("SELECT * FROM employee_skill WHERE tenant_id = #{tenantId} AND employee_id = #{employeeId}")
    List<EmployeeSkillEntity> selectByEmployeeId(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId);

    @Select("SELECT * FROM employee_skill WHERE tenant_id = #{tenantId} AND skill_id = #{skillId}")
    List<EmployeeSkillEntity> selectBySkillId(@Param("tenantId") String tenantId, @Param("skillId") String skillId);

    @Select("SELECT * FROM employee_skill WHERE tenant_id = #{tenantId} AND employee_id = #{employeeId} AND skill_id = #{skillId} LIMIT 1")
    EmployeeSkillEntity selectByEmployeeAndSkill(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId, @Param("skillId") String skillId);
}
