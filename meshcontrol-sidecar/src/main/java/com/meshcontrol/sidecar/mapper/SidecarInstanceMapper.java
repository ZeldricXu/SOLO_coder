package com.meshcontrol.sidecar.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.sidecar.entity.SidecarInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SidecarInstanceMapper extends BaseMapper<SidecarInstance> {

    @Select("SELECT * FROM sidecar_instance WHERE namespace = #{namespace} AND deleted = 0")
    List<SidecarInstance> findByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM sidecar_instance WHERE service_name = #{serviceName} AND deleted = 0")
    List<SidecarInstance> findByServiceName(@Param("serviceName") String serviceName);

    @Select("SELECT * FROM sidecar_instance WHERE status = #{status} AND deleted = 0")
    List<SidecarInstance> findByStatus(@Param("status") String status);

    @Update("UPDATE sidecar_instance SET last_heartbeat = #{heartbeatTime}, status = 'running' WHERE sidecar_id = #{sidecarId}")
    int updateHeartbeat(@Param("sidecarId") String sidecarId,
                        @Param("heartbeatTime") LocalDateTime heartbeatTime);

    @Update("UPDATE sidecar_instance SET status = #{status} WHERE last_heartbeat < #{threshold} AND status = 'running'")
    int markUnhealthyInstances(@Param("threshold") LocalDateTime threshold,
                               @Param("status") String status);
}
