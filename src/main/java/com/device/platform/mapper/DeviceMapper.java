package com.device.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.device.platform.entity.Device;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
