package com.exam.controller;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class QuestionExcelDTO {

    @ExcelProperty("题目标题")
    private String title;

    @ExcelProperty("题目内容")
    private String content;

    @ExcelProperty("题目类型")
    private Integer type;

    @ExcelProperty("难度等级")
    private Integer difficulty;

    @ExcelProperty("分数")
    private BigDecimal score;

    @ExcelProperty("正确答案")
    private String answer;

    @ExcelProperty("解析")
    private String analysis;

    @ExcelProperty("选项A")
    private String optionA;

    @ExcelProperty("选项B")
    private String optionB;

    @ExcelProperty("选项C")
    private String optionC;

    @ExcelProperty("选项D")
    private String optionD;

    @ExcelProperty("编程语言")
    private String programmingLanguage;

    @ExcelProperty("测试用例")
    private String testCases;

    @ExcelProperty("时间限制(ms)")
    private Integer timeLimit;

    @ExcelProperty("内存限制(KB)")
    private Integer memoryLimit;
}
