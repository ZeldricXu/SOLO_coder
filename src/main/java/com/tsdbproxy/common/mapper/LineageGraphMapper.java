package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.LineageGraph;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineageGraphMapper extends BaseMapper<LineageGraph> {
}
