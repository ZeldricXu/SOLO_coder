package com.contractai.document.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.document.entity.Document;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
}
