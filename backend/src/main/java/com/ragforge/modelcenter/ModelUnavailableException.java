package com.ragforge.modelcenter;

import com.ragforge.common.BizException;

/** 某用途下没有任何可用（enabled）的模型可解析时抛出。 */
public class ModelUnavailableException extends BizException {

  public ModelUnavailableException(Purpose purpose) {
    super(409, "MODEL_UNAVAILABLE_FOR_PURPOSE:" + purpose.name());
  }
}
