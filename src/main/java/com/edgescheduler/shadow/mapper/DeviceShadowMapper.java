package com.edgescheduler.shadow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.shadow.entity.DeviceShadow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceShadowMapper extends BaseMapper<DeviceShadow> {

    @Select("SELECT * FROM device_shadow WHERE device_key = #{deviceKey}")
    DeviceShadow selectByDeviceKey(@Param("deviceKey") String deviceKey);
}
