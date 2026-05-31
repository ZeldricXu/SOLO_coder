package com.contractai.document.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.document.entity.DocumentClause;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentClauseMapper extends BaseMapper<DocumentClause> {

    @Select("SELECT * FROM document_clause WHERE document_id = #{documentId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY start_position")
    List<DocumentClause> findByDocumentId(@Param("documentId") Long documentId, @Param("tenantId") Long tenantId);
}
