package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.ColumnSchema;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ColumnSchemaMapper extends BaseMapper<ColumnSchema> {
}
