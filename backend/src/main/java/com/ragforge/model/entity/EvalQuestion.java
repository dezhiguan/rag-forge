package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import lombok.Data;

@Data
@TableName(value = "eval_questions", autoResultMap = true)
public class EvalQuestion {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long datasetId;
  private String question;
  private String expectedDocIds;

  @TableField(value = "expected_text_snippets", typeHandler = JsonbStringTypeHandler.class)
  private String expectedTextSnippets;
}
