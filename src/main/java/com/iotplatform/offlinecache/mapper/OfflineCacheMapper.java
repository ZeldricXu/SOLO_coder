package com.iotplatform.offlinecache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface OfflineCacheMapper extends BaseMapper<OfflineCache> {

    @Select("SELECT * FROM offline_cache WHERE cache_key = #{cacheKey}")
    Optional<OfflineCache> findByCacheKey(@Param("cacheKey") String cacheKey);

    @Select("SELECT * FROM offline_cache WHERE synced = 0 AND sync_attempts < #{maxAttempts} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<OfflineCache> findUnsynced(@Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    @Update("UPDATE offline_cache SET synced = 1, last_sync_attempt_at = #{attemptAt} WHERE id = #{id}")
    int markAsSynced(@Param("id") Long id, @Param("attemptAt") LocalDateTime attemptAt);

    @Update("UPDATE offline_cache SET sync_attempts = sync_attempts + 1, " +
            "last_sync_attempt_at = #{attemptAt}, sync_error = #{error} WHERE id = #{id}")
    int markSyncFailed(@Param("id") Long id,
                       @Param("attemptAt") LocalDateTime attemptAt,
                       @Param("error") String error);

    @Select("SELECT COUNT(*) FROM offline_cache WHERE synced = 0")
    long countUnsynced();

    @Select("SELECT COUNT(*) FROM offline_cache WHERE synced = 0 AND sync_attempts >= #{maxAttempts}")
    long countSyncFailed(@Param("maxAttempts") int maxAttempts);

    @Select("DELETE FROM offline_cache WHERE expire_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
