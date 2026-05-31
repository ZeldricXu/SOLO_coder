package com.chainetl.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.common.model.MetricSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {
}
