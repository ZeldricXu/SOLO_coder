package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.ExamAbnormal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExamAbnormalMapper extends BaseMapper<ExamAbnormal> {

    @Select("SELECT * FROM exam_exam_abnormal WHERE exam_id = #{examId} AND deleted = 0 ORDER BY happen_time DESC")
    List<ExamAbnormal> selectByExamId(@Param("examId") Long examId);

    @Select("SELECT * FROM exam_exam_abnormal WHERE session_id = #{sessionId} AND deleted = 0 ORDER BY happen_time DESC")
    List<ExamAbnormal> selectBySessionId(@Param("sessionId") Long sessionId);
}
