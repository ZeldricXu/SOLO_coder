package com.observability.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.observability.common.entity.ConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConfigMapper extends BaseMapper<ConfigEntity> {

    @Select("SELECT * FROM t_config WHERE namespace = #{namespace} AND enabled = 1 ORDER BY version DESC LIMIT 1")
    ConfigEntity findLatestByNamespace(@Param("namespace") String namespace);

    @Select("SELECT * FROM t_config WHERE config_id = #{configId} ORDER BY version DESC LIMIT 1")
    ConfigEntity findLatestByConfigId(@Param("configId") String configId);

    @Select("SELECT * FROM t_config WHERE namespace = #{namespace} AND enabled = 1")
    List<ConfigEntity> findAllByNamespace(@Param("namespace") String namespace);
}
