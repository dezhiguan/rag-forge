package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("eval_questions")
public class EvalQuestion {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long datasetId;
  private String question;
  private String expectedDocIds;
}
