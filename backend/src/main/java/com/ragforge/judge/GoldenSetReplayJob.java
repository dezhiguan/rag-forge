package com.ragforge.judge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerService;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import com.ragforge.judge.vo.ReplayResultVo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragforge.judge.golden-replay.enabled", havingValue = "true", matchIfMissing = true)
public class GoldenSetReplayJob {

  public static final String SCHEDULER_LOCK_NAME = "judge-golden-replay";

  private final EvalQuestionMapper questionMapper;
  private final EvalDatasetMapper datasetMapper;
  private final AnswerService answerService;
  private final com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

  @Scheduled(cron = "${ragforge.judge.golden-replay.cron:0 0 3 * * *}")
  @SchedulerLock(name = SCHEDULER_LOCK_NAME, lockAtMostFor = "PT2H")
  public void replay() {
    replay(null, Integer.MAX_VALUE);
  }

  public ReplayResultVo replay(Long datasetId, int limit) {
    return runReplay(loadQuestions(datasetId), limit, datasetId);
  }

  /** 组织级回放：只跑 dataset 归属 KB 落在 kbIds 范围内的启用黄金题（样本经 OrgContextHolder 归属该组织）。 */
  public ReplayResultVo replayForKbScope(java.util.Set<Long> kbIds, int limit) {
    return runReplay(loadQuestionsForKbScope(kbIds), limit, null);
  }

  private ReplayResultVo runReplay(List<EvalQuestion> questions, int limit, Long datasetId) {
    ReplayResultVo result = new ReplayResultVo();
    int safeLimit = limit <= 0 ? 0 : limit;
    int requested = safeLimit > 0 ? Math.min(safeLimit, questions.size()) : questions.size();
    int success = 0;
    int failed = 0;
    log.info("Golden replay starting: {} questions", requested);

    for (int i = 0; i < requested; i++) {
      EvalQuestion question = questions.get(i);
      try {
        List<Long> kbIds = parseKbIds(question);
        AnswerRequest req = new AnswerRequest();
        req.setQuery(question.getQuestion());
        req.setKbIds(kbIds);
        req.setJudgeSource("GOLDEN_SET");
        req.setGoldenQuestionId(question.getId());
        req.setForceSample(true);
        req.setStream(false);
        // 回放跑在无请求上下文的线程上:设组织上下文,让应答/检索里的 query 向量化等模型用量
        // 归属到 KB 所属组织(否则记在 org_id=0,组织成本看板看不到)。
        com.ragforge.security.OrgContextHolder.set(resolveOrgId(kbIds));
        try {
          answerService.answerSync(req);
        } finally {
          com.ragforge.security.OrgContextHolder.clear();
        }
        success++;
      } catch (Exception e) {
        failed++;
        log.warn("Golden replay failed for q={}: {}", question.getId(), e.getMessage());
      }
      if (i + 1 < requested) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Golden replay sleep interrupted");
          break;
        }
      }
    }

    log.info("Golden replay done: success={} failed={}", success, failed);
    result.setRequested(requested);
    result.setSuccess(success);
    result.setFailed(failed);
    result.setMessage("done");
    result.setDatasetId(datasetId);
    result.setStartedAt(System.currentTimeMillis());
    return result;
  }

  private List<EvalQuestion> loadQuestions(Long datasetId) {
    LambdaQueryWrapper<EvalQuestion> query = new LambdaQueryWrapper<EvalQuestion>()
        .eq(EvalQuestion::getJudgeEnabled, true)
        .orderByAsc(EvalQuestion::getId);
    if (datasetId != null) {
      query.eq(EvalQuestion::getDatasetId, datasetId);
    }
    return questionMapper.selectList(query);
  }

  private List<EvalQuestion> loadQuestionsForKbScope(java.util.Set<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty()) {
      return List.of();
    }
    List<Long> datasetIds =
        datasetMapper
            .selectList(new LambdaQueryWrapper<EvalDataset>().in(EvalDataset::getKbId, kbIds))
            .stream()
            .map(EvalDataset::getId)
            .toList();
    if (datasetIds.isEmpty()) {
      return List.of();
    }
    return questionMapper.selectList(
        new LambdaQueryWrapper<EvalQuestion>()
            .eq(EvalQuestion::getJudgeEnabled, true)
            .in(EvalQuestion::getDatasetId, datasetIds)
            .orderByAsc(EvalQuestion::getId));
  }

  private List<Long> parseKbIds(EvalQuestion question) {
    EvalDataset dataset = question.getDatasetId() == null ? null : datasetMapper.selectById(question.getDatasetId());
    if (dataset == null || dataset.getKbId() == null) {
      throw new IllegalStateException("GOLDEN_SET_DATASET_KB_REQUIRED");
    }
    return List.of(dataset.getKbId());
  }

  /** 取被评测 KB 所属组织(首个),用于把回放应答/检索的模型用量归属到组织。 */
  private Long resolveOrgId(List<Long> kbIds) {
    if (kbIds == null || kbIds.isEmpty() || kbIds.get(0) == null) {
      return null;
    }
    com.ragforge.model.entity.KnowledgeBase kb = knowledgeBaseMapper.selectById(kbIds.get(0));
    return kb == null ? null : kb.getOrgId();
  }
}
