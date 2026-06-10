package com.exam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.entity.ExamAnswer;
import com.exam.entity.ExamRecord;
import com.exam.entity.WrongBook;

import java.util.List;

public interface WrongBookService {
    void updateWrongBook(ExamAnswer answer, ExamRecord record);

    IPage<WrongBook> getWrongBookPage(Long userId, Long subjectId, Integer questionType,
                                       int pageNum, int pageSize);

    List<WrongBook> getWrongBookList(Long userId, Long subjectId);

    void removeFromWrongBook(Long userId, Long questionId);

    void updateMasteryLevel(Long id, Integer masteryLevel);
}
