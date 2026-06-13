package com.ragforge.service;

import com.ragforge.model.dto.LlmGenerateRequest;
import java.util.Map;

public interface LlmService {

  Map<String, Object> generate(LlmGenerateRequest request);
}
