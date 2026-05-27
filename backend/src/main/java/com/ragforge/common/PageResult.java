package com.ragforge.common;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PageResult<T> {

  private long total;
  private int page;
  private int size;
  private List<T> list;

  public static <T> PageResult<T> of(long total, int page, int size, List<T> list) {
    return new PageResult<>(total, page, size, list);
  }
}
