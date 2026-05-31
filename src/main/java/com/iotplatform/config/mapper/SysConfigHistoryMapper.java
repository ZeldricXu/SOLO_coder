package com.iotplatform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iotplatform.config.entity.SysConfigHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SysConfigHistoryMapper extends BaseMapper<SysConfigHistory> {

    @Select("SELECT * FROM sys_config_history WHERE config_id = #{configId} AND namespace = #{namespace} " +
            "ORDER BY version DESC")
    List<SysConfigHistory> findByConfigId(@Param("configId") String configId,
                                          @Param("namespace") String namespace);

    @Select("SELECT * FROM sys_config_history WHERE config_id = #{configId} AND namespace = #{namespace} " +
            "AND version = #{version}")
    SysConfigHistory findByConfigIdAndVersion(@Param("configId") String configId,
                                               @Param("namespace") String namespace,
                                               @Param("version") Integer version);
}
