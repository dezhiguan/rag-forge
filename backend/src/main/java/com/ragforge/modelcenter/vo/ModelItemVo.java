package com.ragforge.modelcenter.vo;

import java.math.BigDecimal;

/** 模型列表项（含本月费用汇总）。 */
public record ModelItemVo(
    String code,
    String displayName,
    String vendor,
    String purpose,
    BigDecimal inputPrice,
    BigDecimal outputPrice,
    Boolean isLocal,
    Boolean enabled,
    Boolean isPrimary,
    BigDecimal monthlyCost) {}
