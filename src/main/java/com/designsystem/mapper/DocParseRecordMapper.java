package com.designsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.designsystem.entity.DocParseRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocParseRecordMapper extends BaseMapper<DocParseRecord> {

    @Select("SELECT * FROM ds_doc_parse_record WHERE component_id = #{componentId} AND version_id = #{versionId}")
    List<DocParseRecord> selectByComponentAndVersion(@Param("componentId") Long componentId,
                                                     @Param("versionId") Long versionId);

    @Select("SELECT * FROM ds_doc_parse_record WHERE component_id = #{componentId} AND file_path = #{filePath} ORDER BY id DESC LIMIT 1")
    DocParseRecord selectLatestByComponentAndPath(@Param("componentId") Long componentId,
                                                  @Param("filePath") String filePath);

    @Select("SELECT file_path FROM ds_doc_parse_record WHERE component_id = #{componentId} AND parse_status = 1")
    List<String> selectSuccessfullyParsedPaths(@Param("componentId") Long componentId);
}
