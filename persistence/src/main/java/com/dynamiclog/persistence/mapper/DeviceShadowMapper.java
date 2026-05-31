package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.DeviceShadow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceShadowMapper extends BaseMapper<DeviceShadow> {

    @Select("SELECT * FROM device_shadow WHERE device_id = #{deviceId} AND deleted = 0")
    DeviceShadow findByDeviceId(@Param("deviceId") String deviceId);

    @Select("SELECT version FROM device_shadow WHERE device_id = #{deviceId} AND deleted = 0")
    Integer getCurrentVersion(@Param("deviceId") String deviceId);
}
