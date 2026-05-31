package com.meshcontrol.sidecar.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.sidecar.entity.SidecarConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SidecarConfigMapper extends BaseMapper<SidecarConfig> {

    @Select("SELECT * FROM sidecar_config WHERE namespace = #{namespace} AND enabled = 1 AND deleted = 0 ORDER BY version DESC LIMIT 1")
    SidecarConfig findLatestByNamespace(@Param("namespace") String namespace);

    @Select("SELECT MAX(version) FROM sidecar_config WHERE namespace = #{namespace} AND deleted = 0")
    Integer findMaxVersion(@Param("namespace") String namespace);
}
