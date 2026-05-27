package com.ragforge.service;

import com.ragforge.model.dto.CreateEvalDatasetDTO;
import com.ragforge.model.vo.EvalDatasetVO;
import java.util.List;

public interface EvalDatasetService {

  List<EvalDatasetVO> listAll();

  EvalDatasetVO create(CreateEvalDatasetDTO dto);

  EvalDatasetVO getById(Long id);

  void delete(Long id);

  void requireDataset(Long id);
}
