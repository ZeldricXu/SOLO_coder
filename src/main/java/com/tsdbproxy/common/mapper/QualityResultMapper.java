package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.QualityResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityResultMapper extends BaseMapper<QualityResult> {
}
