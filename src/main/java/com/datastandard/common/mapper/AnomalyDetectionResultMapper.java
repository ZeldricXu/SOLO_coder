package com.datastandard.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.common.model.AnomalyDetectionResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnomalyDetectionResultMapper extends BaseMapper<AnomalyDetectionResult> {
}
