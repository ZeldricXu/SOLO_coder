package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExamSessionMapper extends BaseMapper<ExamSession> {

    @Select("SELECT * FROM exam_exam_session WHERE exam_id = #{examId} AND student_id = #{studentId} AND deleted = 0 ORDER BY id DESC LIMIT 1")
    ExamSession selectByExamAndStudent(@Param("examId") Long examId, @Param("studentId") Long studentId);

    @Select("SELECT * FROM exam_exam_session WHERE exam_id = #{examId} AND deleted = 0")
    List<ExamSession> selectByExamId(@Param("examId") Long examId);
}
