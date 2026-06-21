package com.ragforge.service;

import com.ragforge.model.dto.ChunkerAbRequest;
import com.ragforge.model.vo.ChunkerAbResponse;

public interface ChunkerAbService {

  ChunkerAbResponse run(ChunkerAbRequest request);
}
