package com.edgescheduler.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.cache.entity.NetworkStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NetworkStatusMapper extends BaseMapper<NetworkStatus> {

    @Select("SELECT * FROM network_status WHERE status_id = #{statusId} ORDER BY created_at DESC LIMIT 1")
    NetworkStatus selectLatest(@Param("statusId") String statusId);

    @Select("SELECT * FROM network_status ORDER BY created_at DESC LIMIT 1")
    NetworkStatus selectLatestStatus();
}
