package com.ragforge.service;

import com.ragforge.common.PageResult;
import com.ragforge.model.dto.CreateEvalQuestionDTO;
import com.ragforge.model.dto.SaveQuestionFromSearchDTO;
import com.ragforge.model.vo.EvalQuestionVO;
import java.util.List;

public interface EvalQuestionService {

  PageResult<EvalQuestionVO> listByDataset(Long datasetId, int page, int size);

  EvalQuestionVO create(Long datasetId, CreateEvalQuestionDTO dto);

  List<EvalQuestionVO> batchCreate(Long datasetId, List<CreateEvalQuestionDTO> questions);

  EvalQuestionVO createFromSearch(Long datasetId, SaveQuestionFromSearchDTO dto);

  EvalQuestionVO update(Long datasetId, Long questionId, CreateEvalQuestionDTO dto);

  void delete(Long datasetId, Long questionId);
}
