package com.exam.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.common.Result;
import com.exam.entity.WrongBook;
import com.exam.service.WrongBookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/student/wrongbook")
@RequiredArgsConstructor
public class WrongBookController {

    private final WrongBookService wrongBookService;

    @GetMapping("/page")
    public Result<IPage<WrongBook>> getWrongBookPage(
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Integer questionType,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(wrongBookService.getWrongBookPage(userId, subjectId, questionType, pageNum, pageSize));
    }

    @GetMapping("/list")
    public Result<List<WrongBook>> getWrongBookList(
            @RequestParam(required = false) Long subjectId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(wrongBookService.getWrongBookList(userId, subjectId));
    }

    @DeleteMapping("/{questionId}")
    public Result<Void> removeFromWrongBook(@PathVariable Long questionId,
                                            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        wrongBookService.removeFromWrongBook(userId, questionId);
        return Result.success();
    }

    @PutMapping("/{id}/mastery")
    public Result<Void> updateMasteryLevel(@PathVariable Long id,
                                           @RequestParam Integer masteryLevel) {
        wrongBookService.updateMasteryLevel(id, masteryLevel);
        return Result.success();
    }
}
