package com.llmgateway.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.document.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Select("SELECT * FROM document_chunk WHERE document_id = #{documentId} ORDER BY chunk_index ASC")
    List<DocumentChunk> selectByDocumentId(@Param("documentId") String documentId);
}
