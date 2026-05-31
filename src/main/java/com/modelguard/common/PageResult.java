package com.modelguard.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int pageNum;
    private int pageSize;
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
        long pages = total > 0 ? (total + pageSize - 1) / pageSize : 0;
        return new PageResult<>(records, total, pageNum, pageSize, pages);
    }
}
