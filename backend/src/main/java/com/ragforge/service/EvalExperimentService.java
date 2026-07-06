package com.ragforge.service;

import com.ragforge.common.PageResult;
import com.ragforge.model.vo.EvalExperimentVO;
import java.util.List;

public interface EvalExperimentService {

  EvalExperimentVO runExperiment(Long datasetId, String strategy, Double vectorWeight, Integer topK);

  List<EvalExperimentVO> listRecent();

  /** 分页列出实验，datasetName 非空时按数据集名模糊匹配。按组织隔离（同 listRecent）。 */
  PageResult<EvalExperimentVO> list(int page, int size, String datasetName);

  EvalExperimentVO getDetail(Long id);

  void delete(Long id);
}

