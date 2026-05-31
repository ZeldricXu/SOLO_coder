package com.meshcontrol.sidecar.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.sidecar.entity.InjectionPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InjectionPolicyMapper extends BaseMapper<InjectionPolicy> {

    @Select("SELECT * FROM injection_policy WHERE namespace = #{namespace} AND enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<InjectionPolicy> findActivePoliciesByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM injection_policy WHERE enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<InjectionPolicy> findAllActivePolicies();
}
