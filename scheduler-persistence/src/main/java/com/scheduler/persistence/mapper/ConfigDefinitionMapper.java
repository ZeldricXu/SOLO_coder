package com.scheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scheduler.persistence.entity.ConfigDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ConfigDefinitionMapper extends BaseMapper<ConfigDefinition> {

    @Select("SELECT * FROM config_definitions WHERE config_id = #{configId} ORDER BY version DESC LIMIT 1")
    Optional<ConfigDefinition> findLatestByConfigId(@Param("configId") String configId);

    @Select("SELECT * FROM config_definitions WHERE namespace = #{namespace} AND enabled = true")
    List<ConfigDefinition> findEnabledByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM config_definitions WHERE config_id = #{configId} AND version = #{version}")
    Optional<ConfigDefinition> findByConfigIdAndVersion(@Param("configId") String configId, @Param("version") Integer version);
}
