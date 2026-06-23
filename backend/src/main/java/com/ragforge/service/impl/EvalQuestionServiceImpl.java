package com.ragforge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.common.BizException;
import com.ragforge.common.PageResult;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.dto.CreateEvalQuestionDTO;
import com.ragforge.model.dto.SaveQuestionFromSearchDTO;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.model.vo.EvalQuestionVO;
import com.ragforge.service.EvalDatasetService;
import com.ragforge.service.EvalQuestionService;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EvalQuestionServiceImpl implements EvalQuestionService {

  private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  private final EvalQuestionMapper evalQuestionMapper;
  private final EvalDatasetMapper evalDatasetMapper;
  private final EvalDatasetService evalDatasetService;
  private final ObjectMapper objectMapper;

  @Override
  public PageResult<EvalQuestionVO> listByDataset(Long datasetId, int page, int size) {
    evalDatasetService.requireDataset(datasetId);

    Page<EvalQuestion> mpPage = new Page<>(page, size);
    IPage<EvalQuestion> result =
        evalQuestionMapper.selectPage(
            mpPage,
            new LambdaQueryWrapper<EvalQuestion>()
                .eq(EvalQuestion::getDatasetId, datasetId)
                .orderByAsc(EvalQuestion::getId));

    List<EvalQuestionVO> list = result.getRecords().stream().map(this::toVO).toList();
    return PageResult.of(result.getTotal(), (int) mpPage.getCurrent(), size, list);
  }

  @Override
  @Transactional
  public EvalQuestionVO create(Long datasetId, CreateEvalQuestionDTO dto) {
    evalDatasetService.requireDataset(datasetId);
    EvalQuestion question =
        buildQuestion(datasetId, dto.getQuestion(), dto.getExpectedChunkIds(), dto.getExpectedTextSnippets());
    applyJudgeFields(question, dto);
    evalQuestionMapper.insert(question);
    incrementQuestionCount(datasetId, 1);
    return toVO(question);
  }

  @Override
  @Transactional
  public List<EvalQuestionVO> batchCreate(Long datasetId, List<CreateEvalQuestionDTO> questions) {
    evalDatasetService.requireDataset(datasetId);
    if (CollectionUtils.isEmpty(questions)) {
      return Collections.emptyList();
    }

    List<EvalQuestionVO> created =
        questions.stream()
            .map(
                dto -> {
                  EvalQuestion question =
                      buildQuestion(
                          datasetId,
                          dto.getQuestion(),
                          dto.getExpectedChunkIds(),
                          dto.getExpectedTextSnippets());
                  applyJudgeFields(question, dto);
                  evalQuestionMapper.insert(question);
                  return toVO(question);
                })
            .toList();
    incrementQuestionCount(datasetId, created.size());
    return created;
  }

  @Override
  @Transactional
  public EvalQuestionVO createFromSearch(Long datasetId, SaveQuestionFromSearchDTO dto) {
    CreateEvalQuestionDTO createDto = new CreateEvalQuestionDTO();
    createDto.setQuestion(dto.getQuestion());
    createDto.setExpectedChunkIds(
        dto.getSelectedChunkIds() != null ? dto.getSelectedChunkIds() : Collections.emptyList());
    return create(datasetId, createDto);
  }

  @Override
  @Transactional
  public EvalQuestionVO update(Long datasetId, Long questionId, CreateEvalQuestionDTO dto) {
    evalDatasetService.requireDataset(datasetId);
    EvalQuestion question = evalQuestionMapper.selectById(questionId);
    if (question == null || !datasetId.equals(question.getDatasetId())) {
      throw new BizException(404, "评测问题不存在");
    }
    question.setQuestion(dto.getQuestion().trim());
    question.setExpectedDocIds(serializeChunkIds(dto.getExpectedChunkIds()));
    question.setExpectedTextSnippets(serializeTextSnippets(dto.getExpectedTextSnippets()));
    applyJudgeFields(question, dto);
    evalQuestionMapper.updateById(question);
    return toVO(question);
  }

  @Override
  @Transactional
  public void delete(Long datasetId, Long questionId) {
    evalDatasetService.requireDataset(datasetId);

    EvalQuestion question = evalQuestionMapper.selectById(questionId);
    if (question == null || !datasetId.equals(question.getDatasetId())) {
      throw new BizException(404, "评测问题不存在");
    }

    evalQuestionMapper.deleteById(questionId);
    incrementQuestionCount(datasetId, -1);
  }

  private EvalQuestion buildQuestion(
      Long datasetId, String questionText, List<Long> chunkIds, List<String> textSnippets) {
    EvalQuestion question = new EvalQuestion();
    question.setDatasetId(datasetId);
    question.setQuestion(questionText.trim());
    question.setExpectedDocIds(serializeChunkIds(chunkIds));
    question.setExpectedTextSnippets(serializeTextSnippets(textSnippets));
    question.setJudgeEnabled(Boolean.FALSE);
    question.setJudgeTags(null);
    return question;
  }

  private EvalQuestion applyJudgeFields(EvalQuestion question, CreateEvalQuestionDTO dto) {
    question.setJudgeEnabled(Boolean.TRUE.equals(dto.getJudgeEnabled()));
    question.setJudgeTags(dedupTags(dto.getJudgeTags()));
    return question;
  }

  private String[] dedupTags(List<String> tags) {
    if (CollectionUtils.isEmpty(tags)) {
      return null;
    }
    return tags.stream().filter(StringUtils::hasText).map(String::trim).distinct().toArray(String[]::new);
  }

  private void incrementQuestionCount(Long datasetId, int delta) {
    EvalDataset dataset = evalDatasetMapper.selectById(datasetId);
    if (dataset == null) {
      return;
    }
    int count = dataset.getQuestionCount() != null ? dataset.getQuestionCount() : 0;
    dataset.setQuestionCount(Math.max(0, count + delta));
    evalDatasetMapper.updateById(dataset);
  }

  private EvalQuestionVO toVO(EvalQuestion entity) {
    EvalQuestionVO vo = new EvalQuestionVO();
    vo.setId(entity.getId());
    vo.setDatasetId(entity.getDatasetId());
    vo.setQuestion(entity.getQuestion());
    vo.setExpectedChunkIds(parseChunkIds(entity.getExpectedDocIds()));
    vo.setExpectedTextSnippets(parseTextSnippets(entity.getExpectedTextSnippets()));
    vo.setJudgeEnabled(entity.getJudgeEnabled() != null ? entity.getJudgeEnabled() : Boolean.FALSE);
    vo.setJudgeTags(parseTagArray(entity.getJudgeTags()));
    return vo;
  }

  private List<String> parseTagArray(String[] tags) {
    if (tags == null || tags.length == 0) {
      return Collections.emptyList();
    }
    return Arrays.stream(tags)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .toList();
  }

  private String serializeChunkIds(List<Long> chunkIds) {
    if (CollectionUtils.isEmpty(chunkIds)) {
      return "[]";
    }
    try {
      return objectMapper.writeValueAsString(chunkIds);
    } catch (JsonProcessingException e) {
      throw new BizException("期望 chunk ID 序列化失败");
    }
  }

  private List<Long> parseChunkIds(String json) {
    if (!StringUtils.hasText(json)) {
      return Collections.emptyList();
    }
    try {
      return objectMapper.readValue(json, LONG_LIST_TYPE);
    } catch (JsonProcessingException e) {
      return Collections.emptyList();
    }
  }

  private String serializeTextSnippets(List<String> snippets) {
    if (CollectionUtils.isEmpty(snippets)) {
      return "[]";
    }
    List<String> cleaned =
        snippets.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    if (cleaned.isEmpty()) {
      return "[]";
    }
    try {
      return objectMapper.writeValueAsString(cleaned);
    } catch (JsonProcessingException e) {
      throw new BizException("期望文本片段序列化失败");
    }
  }

  private List<String> parseTextSnippets(String json) {
    if (!StringUtils.hasText(json)) {
      return Collections.emptyList();
    }
    try {
      return objectMapper.readValue(json, STRING_LIST_TYPE);
    } catch (JsonProcessingException e) {
      return Collections.emptyList();
    }
  }
}
