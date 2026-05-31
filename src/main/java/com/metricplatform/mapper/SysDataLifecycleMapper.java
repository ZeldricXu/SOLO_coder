package com.metricplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metricplatform.entity.SysDataLifecycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysDataLifecycleMapper extends BaseMapper<SysDataLifecycle> {

    @Select("SELECT COUNT(*) FROM ${tableName} WHERE created_at < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    long countOlderThan(@Param("tableName") String tableName, @Param("days") int days);

    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = #{tableName}")
    int tableExists(@Param("tableName") String tableName);
}
