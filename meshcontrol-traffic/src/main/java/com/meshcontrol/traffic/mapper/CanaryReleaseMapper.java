package com.meshcontrol.traffic.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.traffic.entity.CanaryRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CanaryReleaseMapper extends BaseMapper<CanaryRelease> {

    @Select("SELECT * FROM canary_release WHERE service_name = #{serviceName} AND deleted = 0 ORDER BY created_at DESC")
    List<CanaryRelease> findByServiceName(@Param("serviceName") String serviceName);

    @Select("SELECT * FROM canary_release WHERE status = #{status} AND deleted = 0")
    List<CanaryRelease> findByStatus(@Param("status") String status);
}
