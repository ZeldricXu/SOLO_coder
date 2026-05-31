package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.SampleData;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SampleDataMapper extends BaseMapper<SampleData> {
}
