package com.example.configmanager.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.configmanager.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
