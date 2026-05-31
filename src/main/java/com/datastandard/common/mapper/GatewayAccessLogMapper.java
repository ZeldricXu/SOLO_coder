package com.datastandard.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.common.model.GatewayAccessLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GatewayAccessLogMapper extends BaseMapper<GatewayAccessLog> {
}
