package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("eval_datasets")
public class EvalDataset {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String name;
  private Long kbId;
  private Integer questionCount;
  private LocalDateTime createdAt;
}
