package com.ragforge.service;

import com.ragforge.model.vo.EvalExperimentVO;
import java.util.List;

public interface EvalExperimentService {

  EvalExperimentVO runExperiment(Long datasetId, String strategy, Double vectorWeight, Integer topK);

  List<EvalExperimentVO> listRecent();

  EvalExperimentVO getDetail(Long id);

  void delete(Long id);
}

