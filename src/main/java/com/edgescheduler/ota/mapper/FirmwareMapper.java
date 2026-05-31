package com.edgescheduler.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.ota.entity.Firmware;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FirmwareMapper extends BaseMapper<Firmware> {

    @Select("SELECT * FROM firmware WHERE firmware_id = #{firmwareId} AND deleted = 0")
    Firmware selectByFirmwareId(@Param("firmwareId") String firmwareId);

    @Select("SELECT * FROM firmware WHERE product_key = #{productKey} AND version = #{version} AND deleted = 0")
    Firmware selectByProductKeyAndVersion(@Param("productKey") String productKey,
                                           @Param("version") String version);

    @Select("SELECT * FROM firmware WHERE product_key = #{productKey} AND deleted = 0 ORDER BY created_at DESC")
    List<Firmware> selectByProductKey(@Param("productKey") String productKey);

    @Select("SELECT * FROM firmware WHERE product_key = #{productKey} AND status = 'published' AND deleted = 0 " +
            "ORDER BY created_at DESC LIMIT 1")
    Firmware selectLatestPublished(@Param("productKey") String productKey);
}
