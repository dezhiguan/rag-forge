package com.ragforge.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ragforge.mybatis.handler.JsonbStringTypeHandler;
import com.ragforge.mybatis.handler.StringArrayTypeHandler;
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

  private Boolean judgeEnabled;

  @TableField(value = "judge_tags", typeHandler = StringArrayTypeHandler.class)
  private String[] judgeTags;

  /** 核心题（平台级黄金集冻结基线）：TRUE 时禁止编辑/删除。 */
  private Boolean isCore;
}
