package com.edgeplatform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgeplatform.config.entity.ConfigVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConfigVersionMapper extends BaseMapper<ConfigVersion> {

    @Select("SELECT * FROM config_version WHERE config_id = #{configId} AND deleted = 0 ORDER BY version DESC")
    List<ConfigVersion> findByConfigId(@Param("configId") String configId);

    @Select("SELECT * FROM config_version WHERE config_id = #{configId} AND version = #{version} AND deleted = 0")
    ConfigVersion findByConfigIdAndVersion(@Param("configId") String configId, @Param("version") Integer version);
}
