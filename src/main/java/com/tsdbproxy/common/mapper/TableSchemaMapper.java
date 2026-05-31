package com.tsdbproxy.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tsdbproxy.common.entity.TableSchema;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TableSchemaMapper extends BaseMapper<TableSchema> {
}
