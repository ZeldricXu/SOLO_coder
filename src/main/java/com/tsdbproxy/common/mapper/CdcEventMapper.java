package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.CdcEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CdcEventMapper extends BaseMapper<CdcEvent> {
}
