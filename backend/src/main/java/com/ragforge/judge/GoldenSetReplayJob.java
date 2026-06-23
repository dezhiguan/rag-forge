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

  @Scheduled(cron = "${ragforge.judge.golden-replay.cron:0 0 3 * * *}")
  @SchedulerLock(name = SCHEDULER_LOCK_NAME, lockAtMostFor = "PT2H")
  public void replay() {
    replay(null, Integer.MAX_VALUE);
  }

  public ReplayResultVo replay(Long datasetId, int limit) {
    ReplayResultVo result = new ReplayResultVo();
    int safeLimit = limit <= 0 ? 0 : limit;
    List<EvalQuestion> questions = loadQuestions(datasetId);
    int requested = safeLimit > 0 ? Math.min(safeLimit, questions.size()) : questions.size();
    int success = 0;
    int failed = 0;
    log.info("Golden replay starting: {} questions", requested);

    for (int i = 0; i < requested; i++) {
      EvalQuestion question = questions.get(i);
      try {
        AnswerRequest req = new AnswerRequest();
        req.setQuery(question.getQuestion());
        req.setKbIds(parseKbIds(question));
        req.setJudgeSource("GOLDEN_SET");
        req.setGoldenQuestionId(question.getId());
        req.setForceSample(true);
        req.setStream(false);
        answerService.answerSync(req);
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

  private List<Long> parseKbIds(EvalQuestion question) {
    EvalDataset dataset = question.getDatasetId() == null ? null : datasetMapper.selectById(question.getDatasetId());
    if (dataset == null || dataset.getKbId() == null) {
      throw new IllegalStateException("GOLDEN_SET_DATASET_KB_REQUIRED");
    }
    return List.of(dataset.getKbId());
  }
}
