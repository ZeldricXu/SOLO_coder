package com.taskplatform.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskplatform.persistence.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
}
