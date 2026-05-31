package com
.meshcontrol.dns.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.dns.entity.DnsUpstream;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DnsUpstreamMapper extends BaseMapper<DnsUpstream> {

    @Select("SELECT * FROM dns_upstream WHERE enabled = true AND health_status = 'healthy' ORDER BY priority ASC")
    List<DnsUpstream> findAllEnabled();

    @Select("SELECT * FROM dns_upstream WHERE upstream_id = #{id} AND enabled = true")
    List<DnsUpstream> findEnabledByIds(@Param("id") String id);
}
