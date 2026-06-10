package com.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.entity.WrongBook;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface WrongBookMapper extends BaseMapper<WrongBook> {

    @Select("SELECT * FROM exam_wrong_book WHERE student_id = #{studentId} AND question_id = #{questionId} AND deleted = 0 LIMIT 1")
    WrongBook selectByStudentAndQuestion(@Param("studentId") Long studentId, @Param("questionId") Long questionId);

    @Select("SELECT * FROM exam_wrong_book WHERE student_id = #{studentId} AND deleted = 0 ORDER BY last_wrong_time DESC")
    List<WrongBook> selectByStudentId(@Param("studentId") Long studentId);
}
