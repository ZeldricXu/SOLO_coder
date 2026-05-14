package com.library.librarymgmt.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class BorrowRequest {
    @NotBlank(message = "图书ID不能为空")
    private String book_id;

    @NotBlank(message = "读者ID不能为空")
    private String reader_id;
}
