package com.hxj.oa.common.api;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果。
 */
@Data
public class PageResult<T> implements Serializable {

    /** 总记录数（不是当前页条数） */
    private long total;
    /** 当前页码，从 1 开始 */
    private long pageNum;
    /** 每页条数 */
    private long pageSize;
    /** 总页数 */
    private long pages;
    /** ⚠ **列表数据在这里**，不在 data 上。返回空时为 []，不会为 null */
    private List<T> records;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> records) {
        PageResult<T> p = new PageResult<>();
        p.setTotal(total);
        p.setPageNum(pageNum);
        p.setPageSize(pageSize);
        p.setPages(pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize);
        p.setRecords(records == null ? Collections.emptyList() : records);
        return p;
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return of(0, pageNum, pageSize, Collections.emptyList());
    }
}
