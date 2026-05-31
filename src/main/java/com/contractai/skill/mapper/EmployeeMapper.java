package com.contractai.skill.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.skill.entity.Employee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {

    @Select("SELECT * FROM employee WHERE tenant_id = #{tenantId} AND deleted = 0 ORDER BY created_at DESC")
    List<Employee> findAllByTenantId(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM employee WHERE tenant_id = #{tenantId} AND id IN (${employeeIds}) AND deleted = 0")
    List<Employee> findByIds(@Param("tenantId") Long tenantId, @Param("employeeIds") String employeeIds);
}
