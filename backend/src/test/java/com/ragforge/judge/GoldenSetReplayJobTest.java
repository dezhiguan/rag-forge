package com.ragforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ragforge.answer.AnswerModels.AnswerRequest;
import com.ragforge.answer.AnswerModels.AnswerResponse;
import com.ragforge.answer.AnswerService;
import com.ragforge.judge.vo.ReplayResultVo;
import com.ragforge.mapper.EvalDatasetMapper;
import com.ragforge.mapper.EvalQuestionMapper;
import com.ragforge.model.entity.EvalDataset;
import com.ragforge.model.entity.EvalQuestion;
import java.lang.reflect.Method;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class GoldenSetReplayJobTest {

  @Mock private EvalQuestionMapper questionMapper;
  @Mock private EvalDatasetMapper datasetMapper;
  @Mock private AnswerService answerService;
  @Mock private com.ragforge.mapper.KnowledgeBaseMapper knowledgeBaseMapper;

  @Test
  void replay_runsFiveQuestionsDryRunWithForcedGoldenSampling() {
    when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(questions(5));
    when(datasetMapper.selectById(7L)).thenReturn(dataset(99L));
    when(answerService.answerSync(any(AnswerRequest.class))).thenReturn(new AnswerResponse());

    GoldenSetReplayJob job = new GoldenSetReplayJob(questionMapper, datasetMapper, answerService, knowledgeBaseMapper);
    ReplayResultVo result = job.replay(null, 5);

    assertThat(result.getRequested()).isEqualTo(5);
    assertThat(result.getSuccess()).isEqualTo(5);
    assertThat(result.getFailed()).isZero();

    ArgumentCaptor<AnswerRequest> captor = ArgumentCaptor.forClass(AnswerRequest.class);
    verify(answerService, times(5)).answerSync(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(
            req -> {
              assertThat(req.getKbIds()).containsExactly(99L);
              assertThat(req.getJudgeSource()).isEqualTo("GOLDEN_SET");
              assertThat(req.getForceSample()).isTrue();
              assertThat(req.isStream()).isFalse();
            });
  }

  @Test
  void replayForKbScope_只跑本组织KB范围内启用题() {
    // dataset#7 归属 kb99；启用题 3 道
    when(datasetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(dataset(99L)));
    when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(questions(3));
    when(datasetMapper.selectById(7L)).thenReturn(dataset(99L));
    when(answerService.answerSync(any(AnswerRequest.class))).thenReturn(new AnswerResponse());

    GoldenSetReplayJob job =
        new GoldenSetReplayJob(questionMapper, datasetMapper, answerService, knowledgeBaseMapper);
    ReplayResultVo result = job.replayForKbScope(java.util.Set.of(99L), 50);

    assertThat(result.getRequested()).isEqualTo(3);
    assertThat(result.getSuccess()).isEqualTo(3);
    verify(answerService, times(3)).answerSync(any(AnswerRequest.class));
  }

  @Test
  void replayForKbScope_空范围_不回放() {
    GoldenSetReplayJob job =
        new GoldenSetReplayJob(questionMapper, datasetMapper, answerService, knowledgeBaseMapper);
    ReplayResultVo result = job.replayForKbScope(java.util.Set.of(), 50);

    assertThat(result.getRequested()).isZero();
    assertThat(result.getSuccess()).isZero();
  }

  @Test
  void scheduledReplay_hasShedLockAndCron() throws NoSuchMethodException {
    Method method = GoldenSetReplayJob.class.getMethod("replay");
    SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
    Scheduled scheduled = method.getAnnotation(Scheduled.class);

    assertThat(lock).isNotNull();
    assertThat(lock.name()).isEqualTo("judge-golden-replay");
    assertThat(lock.lockAtMostFor()).isEqualTo("PT2H");
    assertThat(scheduled).isNotNull();
  }

  private List<EvalQuestion> questions(int count) {
    return java.util.stream.LongStream.rangeClosed(1, count)
        .mapToObj(
            id -> {
              EvalQuestion question = new EvalQuestion();
              question.setId(id);
              question.setDatasetId(7L);
              question.setQuestion("question-" + id);
              question.setJudgeEnabled(true);
              return question;
            })
        .toList();
  }

  private EvalDataset dataset(Long kbId) {
    EvalDataset dataset = new EvalDataset();
    dataset.setId(7L);
    dataset.setKbId(kbId);
    return dataset;
  }
}
