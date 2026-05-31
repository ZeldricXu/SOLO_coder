package com.datastandard.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.common.model.MetricSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {
}
