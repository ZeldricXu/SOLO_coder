package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.DocParseRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocParseRecordMapper extends BaseMapper<DocParseRecord> {

    @Select("SELECT * FROM ds_doc_parse_record WHERE component_version_id = #{versionId} AND file_path = #{filePath}")
    DocParseRecord selectByVersionAndPath(@Param("versionId") Long versionId, @Param("filePath") String filePath);

    @Select("SELECT * FROM ds_doc_parse_record WHERE component_version_id = #{versionId}")
    List<DocParseRecord> selectByVersionId(@Param("versionId") Long versionId);

    @Select("SELECT file_path FROM ds_doc_parse_record WHERE component_version_id = #{versionId} AND parse_status = 'SUCCESS'")
    List<String> selectSuccessfullyParsedPaths(@Param("versionId") Long versionId);

    @Select("SELECT file_hash FROM ds_doc_parse_record WHERE component_version_id = #{versionId} AND file_path = #{filePath}")
    String getFileHash(@Param("versionId") Long versionId, @Param("filePath") String filePath);
}
