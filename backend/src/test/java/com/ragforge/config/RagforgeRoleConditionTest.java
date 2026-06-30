package com.ragforge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.judge.AnswerJudgeConsumer;
import com.ragforge.judge.JudgeOrchestrator;
import com.ragforge.mapper.DocumentMapper;
import com.ragforge.mapper.AnswerLogMapper;
import com.ragforge.mapper.DocumentChunkMapper;
import com.ragforge.mapper.JudgeResultMapper;
import com.ragforge.mapper.KnowledgeBaseMapper;
import com.ragforge.metrics.RagforgeMetrics;
import com.ragforge.judge.JudgeScorer;
import com.ragforge.mq.DocumentProcessConsumer;
import com.ragforge.pipeline.DocumentPipelineService;
import com.ragforge.pipeline.image.ImagePipelineService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RagforgeRoleConditionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(ConsumerTestConfig.class);

  @Test
  void apiRole_doesNotRegisterDocumentProcessConsumer() {
    contextRunner
        .withPropertyValues("ragforge.role=api")
        .run(context -> assertThat(context).doesNotHaveBean(DocumentProcessConsumer.class));
  }

  @Test
  void apiRole_doesNotRegisterAnswerJudgeConsumer() {
    contextRunner
        .withPropertyValues("ragforge.role=api")
        .run(context -> assertThat(context).doesNotHaveBean(AnswerJudgeConsumer.class));
  }

  @Test
  void workerRole_registersDocumentProcessConsumer() {
    contextRunner
        .withPropertyValues("ragforge.role=worker")
        .run(context -> assertThat(context).hasSingleBean(DocumentProcessConsumer.class));
  }

  @Test
  void judgeRole_registersAnswerJudgeConsumer() {
    contextRunner
        .withPropertyValues("ragforge.role=judge")
        .run(context -> assertThat(context).hasSingleBean(AnswerJudgeConsumer.class));
  }

  @Test
  void judgeRole_doesNotRegisterDocumentProcessConsumer() {
    contextRunner
        .withPropertyValues("ragforge.role=judge")
        .run(context -> assertThat(context).doesNotHaveBean(DocumentProcessConsumer.class));
  }

  @Test
  void missingRole_defaultsToAllAndRegistersDocumentProcessConsumer() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(DocumentProcessConsumer.class));
  }

  @Test
  void workerRole_prunesRestControllers() {
    new ApplicationContextRunner()
        .withPropertyValues("ragforge.role=worker")
        .withUserConfiguration(WorkerWebConfig.class)
        .run(context -> assertThat(context).doesNotHaveBean(TestController.class));
  }

  @Test
  void judgeRole_prunesRestControllers() {
    new ApplicationContextRunner()
        .withPropertyValues("ragforge.role=judge")
        .withUserConfiguration(WorkerWebConfig.class)
        .run(context -> assertThat(context).doesNotHaveBean(TestController.class));
  }

  @Test
  void workerRole_requiresNonWebApplicationType() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("ragforge.role", "worker")
            .withProperty("spring.main.web-application-type", "servlet");

    assertThatThrownBy(
            () -> new WebApplicationTypeGuard(environment).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("web-application-type=none");
  }

  @Test
  void judgeRole_requiresNonWebApplicationType() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("ragforge.role", "judge")
            .withProperty("spring.main.web-application-type", "servlet");

    assertThatThrownBy(
            () -> new WebApplicationTypeGuard(environment).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("web-application-type=none");
  }

  @Configuration
  @Import({DocumentProcessConsumer.class, AnswerJudgeConsumer.class})
  static class ConsumerTestConfig {

    @Bean
    DocumentMapper documentMapper() {
      return mock(DocumentMapper.class);
    }

    @Bean
    KnowledgeBaseMapper knowledgeBaseMapper() {
      return mock(KnowledgeBaseMapper.class);
    }

    @Bean
    AnswerLogMapper answerLogMapper() {
      return mock(AnswerLogMapper.class);
    }

    @Bean
    DocumentChunkMapper documentChunkMapper() {
      return mock(DocumentChunkMapper.class);
    }

    @Bean
    JudgeResultMapper judgeResultMapper() {
      return mock(JudgeResultMapper.class);
    }

    @Bean
    JudgeScorer judgeScorer() {
      return mock(JudgeScorer.class);
    }

    @Bean
    JudgeOrchestrator judgeOrchestrator() {
      return mock(JudgeOrchestrator.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    DocumentPipelineService documentPipelineService() {
      return mock(DocumentPipelineService.class);
    }

    @Bean
    ImagePipelineService imagePipelineService() {
      return mock(ImagePipelineService.class);
    }

    @Bean
    RagforgeMetrics ragforgeMetrics() {
      return new RagforgeMetrics(new SimpleMeterRegistry());
    }
  }

  @Configuration
  @Import({RoleBasedWebBeanPruner.class, TestController.class})
  static class WorkerWebConfig {}

  @RestController
  static class TestController {
    @GetMapping("/test")
    String test() {
      return "ok";
    }
  }
}
