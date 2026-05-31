package com.edgescheduler.protocol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.protocol.entity.ProtocolDriver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProtocolDriverMapper extends BaseMapper<ProtocolDriver> {

    @Select("SELECT * FROM protocol_driver WHERE driver_id = #{driverId}")
    ProtocolDriver selectByDriverId(@Param("driverId") String driverId);
}
