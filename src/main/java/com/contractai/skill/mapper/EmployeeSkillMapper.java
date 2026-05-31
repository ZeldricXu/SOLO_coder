package com.contractai.skill.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.skill.entity.EmployeeSkill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmployeeSkillMapper extends BaseMapper<EmployeeSkill> {

    @Select("SELECT es.* FROM employee_skill es INNER JOIN employee e ON es.employee_id = e.id " +
            "WHERE es.tenant_id = #{tenantId} AND es.skill_id IN (${skillIds}) AND e.deleted = 0 AND es.deleted = 0")
    List<EmployeeSkill> findBySkillIds(@Param("tenantId") Long tenantId, @Param("skillIds") String skillIds);

    @Select("SELECT es.* FROM employee_skill es WHERE es.tenant_id = #{tenantId} AND es.employee_id = #{employeeId} AND es.deleted = 0")
    List<EmployeeSkill> findByEmployeeId(@Param("tenantId") Long tenantId, @Param("employeeId") Long employeeId);
}
