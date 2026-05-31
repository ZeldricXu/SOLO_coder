package com.edgescheduler.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.cache.entity.OfflineCacheData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OfflineCacheDataMapper extends BaseMapper<OfflineCacheData> {

    @Select("SELECT * FROM offline_cache_data WHERE cache_id = #{cacheId}")
    OfflineCacheData selectByCacheId(@Param("cacheId") String cacheId);

    @Select("SELECT * FROM offline_cache_data WHERE status = #{status} ORDER BY priority DESC, cached_at ASC LIMIT #{limit}")
    List<OfflineCacheData> selectPendingSync(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM offline_cache_data WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT SUM(payload_size) FROM offline_cache_data WHERE status = #{status}")
    Long sumPayloadSizeByStatus(@Param("status") String status);

    @Update("UPDATE offline_cache_data SET status = 'expired' WHERE status = 'pending' " +
            "AND cached_at < #{expireTime}")
    int expireOldData(@Param("expireTime") LocalDateTime expireTime);

    @Select("SELECT * FROM offline_cache_data WHERE device_key = #{deviceKey} " +
            "ORDER BY cached_at DESC LIMIT #{limit}")
    List<OfflineCacheData> selectByDeviceKey(@Param("deviceKey") String deviceKey, @Param("limit") int limit);
}
