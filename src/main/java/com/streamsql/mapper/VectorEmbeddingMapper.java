package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.VectorEmbedding;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VectorEmbeddingMapper extends BaseMapper<VectorEmbedding> {
}
