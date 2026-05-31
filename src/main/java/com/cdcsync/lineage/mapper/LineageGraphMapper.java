package com.cdcsync.lineage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cdcsync.lineage.domain.LineageGraph;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineageGraphMapper extends BaseMapper<LineageGraph> {
}
