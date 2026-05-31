package com.meshcontrol.common.response;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;
    private int pageNum;
    private int pageSize;
    private List<T> records;

    public static <T> PageResponse<T> of(IPage<T> page) {
        return new PageResponse<>(page.getTotal(), (int) page.getCurrent(), (int) page.getSize(), page.getRecords());
    }

    public static <T> PageResponse<T> of(long total, int pageNum, int pageSize, List<T> records) {
        return new PageResponse<>(total, pageNum, pageSize, records);
    }
}
