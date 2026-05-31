package com.datastandard.modules.slo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SloMapper extends BaseMapper<SloDefinition> {

    @Select("SELECT * FROM slo_definitions WHERE slo_id = #{sloId} AND deleted = 0")
    Optional<SloDefinition> findById(@Param("sloId") String sloId);

    @Select("SELECT * FROM slo_definitions WHERE service_name = #{serviceName} AND deleted = 0 ORDER BY created_at DESC")
    List<SloDefinition> findByServiceName(@Param("serviceName") String serviceName);

    @Select("SELECT * FROM slo_definitions WHERE environment = #{environment} AND deleted = 0 ORDER BY created_at DESC")
    List<SloDefinition> findByEnvironment(@Param("environment") String environment);

    @Select("SELECT * FROM slo_definitions WHERE enabled = 1 AND deleted = 0 ORDER BY created_at DESC")
    List<SloDefinition> findAllEnabled();

    @Select("SELECT * FROM slo_definitions WHERE service_name = #{serviceName} AND environment = #{environment} AND deleted = 0")
    List<SloDefinition> findByServiceAndEnvironment(
            @Param("serviceName") String serviceName,
            @Param("environment") String environment);
}
