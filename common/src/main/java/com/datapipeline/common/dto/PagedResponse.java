package com.datapipeline.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;
    private boolean hasNext;

    public static <T> PagedResponse<T> of(List<T> items, long total, int page, int size) {
        return PagedResponse.<T>builder()
                .items(items)
                .total(total)
                .page(page)
                .size(size)
                .hasNext((long) page * size < total)
                .build();
    }

}
