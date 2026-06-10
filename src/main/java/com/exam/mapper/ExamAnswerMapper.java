package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamAnswer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExamAnswerMapper extends BaseMapper<ExamAnswer> {

    @Select("SELECT ea.* FROM exam_answer ea " +
            "WHERE ea.exam_record_id = #{examRecordId} AND ea.deleted = 0 " +
            "ORDER BY ea.sort_order ASC")
    List<ExamAnswer> selectByExamRecordId(@Param("examRecordId") Long examRecordId);
}
