package com.edgescheduler.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.device.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @Select("SELECT * FROM device WHERE device_key = #{deviceKey} AND deleted = 0")
    Device selectByDeviceKey(@Param("deviceKey") String deviceKey);

    @Select("SELECT * FROM device WHERE product_key = #{productKey} AND deleted = 0")
    List<Device> selectByProductKey(@Param("productKey") String productKey);

    @Update("UPDATE device SET status = #{status}, last_online_at = #{lastOnlineAt}, updated_at = NOW() " +
            "WHERE device_key = #{deviceKey} AND deleted = 0")
    int updateStatus(@Param("deviceKey") String deviceKey,
                     @Param("status") String status,
                     @Param("lastOnlineAt") LocalDateTime lastOnlineAt);

    @Select("SELECT COUNT(*) FROM device WHERE product_key = #{productKey} AND status = #{status} AND deleted = 0")
    int countByProductKeyAndStatus(@Param("productKey") String productKey,
                                   @Param("status") String status);
}
