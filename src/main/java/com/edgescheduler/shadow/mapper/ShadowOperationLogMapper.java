package com.edgescheduler.shadow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.shadow.entity.ShadowOperationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShadowOperationLogMapper extends BaseMapper<ShadowOperationLog> {

    @Select("SELECT * FROM shadow_operation_log WHERE device_key = #{deviceKey} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<ShadowOperationLog> selectByDeviceKey(@Param("deviceKey") String deviceKey,
                                                @Param("limit") int limit);
}
