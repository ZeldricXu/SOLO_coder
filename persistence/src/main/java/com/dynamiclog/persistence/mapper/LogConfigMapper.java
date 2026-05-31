package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.LogConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface LogConfigMapper extends BaseMapper<LogConfig> {

    @Select("SELECT * FROM log_config WHERE logger_name = #{loggerName} AND namespace = #{namespace} AND deleted = 0")
    LogConfig findByLoggerNameAndNamespace(@Param("loggerName") String loggerName, @Param("namespace") String namespace);

    @Select("SELECT * FROM log_config WHERE namespace = #{namespace} AND deleted = 0")
    List<LogConfig> findByNamespace(@Param("namespace") String namespace);
}
