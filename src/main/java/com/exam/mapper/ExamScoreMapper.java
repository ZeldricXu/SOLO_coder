package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamScore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExamScoreMapper extends BaseMapper<ExamScore> {

    @Select("SELECT * FROM exam_score WHERE exam_id = #{examId} AND student_id = #{studentId} AND deleted = 0 LIMIT 1")
    ExamScore selectByExamAndStudent(@Param("examId") Long examId, @Param("studentId") Long studentId);

    @Select("SELECT * FROM exam_score WHERE exam_id = #{examId} AND deleted = 0")
    List<ExamScore> selectByExamId(@Param("examId") Long examId);
}
