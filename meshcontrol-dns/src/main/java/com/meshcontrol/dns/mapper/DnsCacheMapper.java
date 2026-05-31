package com.meshcontrol.dns.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.dns.entity.DnsCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DnsCacheMapper extends BaseMapper<DnsCache> {

    @Select("SELECT * FROM dns_cache WHERE cache_key = #{cacheKey} AND expires_at > #{now}")
    DnsCache findValidCache(@Param("cacheKey") String cacheKey, @Param("now") LocalDateTime now);

    @Update("UPDATE dns_cache SET hit_count = hit_count + 1 WHERE id = #{id}")
    int incrementHitCount(@Param("id") Long id);

    @Update("DELETE FROM dns_cache WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
