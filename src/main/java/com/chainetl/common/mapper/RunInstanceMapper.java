package com.chainetl.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chainetl.common.model.RunInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RunInstanceMapper extends BaseMapper<RunInstance> {
}
