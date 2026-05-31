package com.edgescheduler.protocol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.protocol.entity.ProtocolAdapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProtocolAdapterMapper extends BaseMapper<ProtocolAdapter> {

    @Select("SELECT * FROM protocol_adapter WHERE adapter_id = #{adapterId}")
    ProtocolAdapter selectByAdapterId(@Param("adapterId") String adapterId);

    @Select("SELECT * FROM protocol_adapter WHERE device_key = #{deviceKey}")
    List<ProtocolAdapter> selectByDeviceKey(@Param("deviceKey") String deviceKey);

    @Select("SELECT * FROM protocol_adapter WHERE driver_id = #{driverId}")
    List<ProtocolAdapter> selectByDriverId(@Param("driverId") String driverId);
}
