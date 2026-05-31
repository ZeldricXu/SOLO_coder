package com.taskflow.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.data.entity.ResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourceEntity> {

    @Select("SELECT * FROM resource WHERE tenant_id = #{tenantId} AND resource_id = #{resourceId}")
    ResourceEntity selectByTenantAndId(@Param("tenantId") String tenantId, @Param("resourceId") String resourceId);

    @Select("SELECT * FROM resource WHERE tenant_id = #{tenantId} AND type = #{type}")
    List<ResourceEntity> selectByTenantAndType(@Param("tenantId") String tenantId, @Param("type") String type);

    @Select("SELECT * FROM resource WHERE tenant_id = #{tenantId} AND status = #{status}")
    List<ResourceEntity> selectByTenantAndStatus(@Param("tenantId") String tenantId, @Param("status") String status);
}
