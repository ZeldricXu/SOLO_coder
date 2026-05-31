package com.solo.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.config.entity.MetricSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricSnapshotMapper extends BaseMapper<MetricSnapshot> {
}
