package com.meshcontrol.dns.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.dns.entity.DnsZone;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DnsZoneMapper extends BaseMapper<DnsZone> {

    @Select("SELECT * FROM dns_zone WHERE domain = #{domain} AND enabled = 1 AND deleted = 0 LIMIT 1")
    DnsZone findByDomain(@Param("domain") String domain;

    @Select("SELECT * FROM dns_zone WHERE #{queryDomain} LIKE CONCAT('%', domain) AND enabled = 1 AND deleted = 0 ORDER BY LENGTH(domain) DESC LIMIT 1")
    DnsZone findBestMatch(@Param("queryDomain") String queryDomain);
}
