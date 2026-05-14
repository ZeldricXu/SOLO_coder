package com.library.librarymgmt.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class BookRequest {
    @NotBlank(message = "图书名称不能为空")
    private String book_name;

    @NotBlank(message = "作者不能为空")
    private String book_author;

    @NotBlank(message = "分类不能为空")
    private String book_category;

    private String book_publisher;

    @NotNull(message = "库存数量不能为空")
    private Integer book_stock;
}
