package com.exam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.entity.ExamAnswer;
import com.exam.entity.ExamRecord;
import com.exam.entity.Question;
import com.exam.entity.WrongBook;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.WrongBookMapper;
import com.exam.service.WrongBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WrongBookServiceImpl implements WrongBookService {

    private final WrongBookMapper wrongBookMapper;
    private final QuestionMapper questionMapper;

    @Override
    @Transactional
    public void updateWrongBook(ExamAnswer answer, ExamRecord record) {
        if (answer.getIsCorrect() == null || answer.getIsCorrect() == 1) {
            return;
        }

        Question question = questionMapper.selectById(answer.getQuestionId());
        if (question == null) {
            return;
        }

        WrongBook wrongBook = wrongBookMapper.selectOne(
                new LambdaQueryWrapper<WrongBook>()
                        .eq(WrongBook::getUserId, record.getUserId())
                        .eq(WrongBook::getQuestionId, answer.getQuestionId()));

        if (wrongBook != null) {
            wrongBook.setWrongCount(wrongBook.getWrongCount() + 1);
            wrongBook.setUserAnswer(answer.getUserAnswer());
            wrongBook.setLastWrongTime(LocalDateTime.now());
            wrongBook.setMasteryLevel(calculateMasteryLevel(wrongBook.getWrongCount() + 1));
            wrongBookMapper.updateById(wrongBook);
        } else {
            wrongBook = new WrongBook();
            wrongBook.setUserId(record.getUserId());
            wrongBook.setQuestionId(answer.getQuestionId());
            wrongBook.setQuestionType(answer.getQuestionType());
            wrongBook.setSubjectId(question.getSubjectId());
            wrongBook.setExamId(answer.getExamId());
            wrongBook.setUserAnswer(answer.getUserAnswer());
            wrongBook.setCorrectAnswer(question.getAnswer());
            wrongBook.setWrongCount(1);
            wrongBook.setMasteryLevel(1);
            wrongBook.setLastWrongTime(LocalDateTime.now());
            wrongBook.setKnowledgePointIds(question.getKnowledgePointIds());
            wrongBookMapper.insert(wrongBook);
        }

        log.debug("错题本更新: userId={}, questionId={}", record.getUserId(), answer.getQuestionId());
    }

    @Override
    public IPage<WrongBook> getWrongBookPage(Long userId, Long subjectId, Integer questionType,
                                              int pageNum, int pageSize) {
        LambdaQueryWrapper<WrongBook> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WrongBook::getUserId, userId);
        if (subjectId != null) {
            wrapper.eq(WrongBook::getSubjectId, subjectId);
        }
        if (questionType != null) {
            wrapper.eq(WrongBook::getQuestionType, questionType);
        }
        wrapper.orderByDesc(WrongBook::getLastWrongTime);

        Page<WrongBook> page = new Page<>(pageNum, pageSize);
        return wrongBookMapper.selectPage(page, wrapper);
    }

    @Override
    public List<WrongBook> getWrongBookList(Long userId, Long subjectId) {
        LambdaQueryWrapper<WrongBook> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WrongBook::getUserId, userId);
        if (subjectId != null) {
            wrapper.eq(WrongBook::getSubjectId, subjectId);
        }
        wrapper.orderByDesc(WrongBook::getLastWrongTime);

        return wrongBookMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public void removeFromWrongBook(Long userId, Long questionId) {
        wrongBookMapper.delete(
                new LambdaQueryWrapper<WrongBook>()
                        .eq(WrongBook::getUserId, userId)
                        .eq(WrongBook::getQuestionId, questionId));
    }

    @Override
    @Transactional
    public void updateMasteryLevel(Long id, Integer masteryLevel) {
        WrongBook wrongBook = wrongBookMapper.selectById(id);
        if (wrongBook != null) {
            wrongBook.setMasteryLevel(masteryLevel);
            wrongBookMapper.updateById(wrongBook);
        }
    }

    private Integer calculateMasteryLevel(int wrongCount) {
        if (wrongCount <= 1) {
            return 1;
        } else if (wrongCount <= 3) {
            return 2;
        } else {
            return 3;
        }
    }
}
