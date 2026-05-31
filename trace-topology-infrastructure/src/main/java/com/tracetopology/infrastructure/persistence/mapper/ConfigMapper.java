package com.tracetopology.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tracetopology.infrastructure.persistence.entity.ConfigPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConfigMapper extends BaseMapper<ConfigPO> {

    @Select("SELECT * FROM t_config WHERE namespace = #{namespace} ORDER BY version DESC LIMIT 1")
    ConfigPO findLatestByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM t_config WHERE config_id = #{configId} ORDER BY version DESC")
    List<ConfigPO> findVersions(@Param("configId") String configId);

    @Select("SELECT * FROM t_config WHERE config_id = #{configId} AND version = #{version}")
    ConfigPO findByIdAndVersion(@Param("configId") String configId, @Param("version") int version);
}
