package com.meshcontrol.traffic.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.traffic.entity.TrafficPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TrafficPolicyMapper extends BaseMapper<TrafficPolicy> {

    @Select("SELECT * FROM traffic_policy WHERE service_name = #{serviceName} AND enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<TrafficPolicy> findByServiceName(@Param("serviceName") String serviceName);

    @Select("SELECT * FROM traffic_policy WHERE type = #{type} AND enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<TrafficPolicy> findByType(@Param("type") String type);

    @Select("SELECT * FROM traffic_policy WHERE namespace = #{namespace} AND enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<TrafficPolicy> findByNamespace(@Param("namespace") String namespace);
}
