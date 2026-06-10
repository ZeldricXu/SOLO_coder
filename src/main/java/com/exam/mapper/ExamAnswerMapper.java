package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamAnswer;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExamAnswerMapper extends BaseMapper<ExamAnswer> {

    @Select("SELECT * FROM exam_exam_answer WHERE session_id = #{sessionId} AND deleted = 0 ORDER BY question_order")
    List<ExamAnswer> selectBySessionId(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM exam_exam_answer WHERE session_id = #{sessionId} AND question_id = #{questionId} AND deleted = 0 LIMIT 1")
    ExamAnswer selectBySessionAndQuestion(@Param("sessionId") Long sessionId, @Param("questionId") Long questionId);
}
