package com.ragforge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

class MdcContextTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void wrap_runnable_propagatesMdcToChildThread() throws InterruptedException {
    MDC.put("traceId", "test-trace-123");

    AtomicReference<String> capturedTraceId = new AtomicReference<>();
    Runnable wrapped = MdcContext.wrap(() -> capturedTraceId.set(MDC.get("traceId")));

    MDC.clear(); // Simulate MDC being cleared in a new thread
    Thread thread = new Thread(wrapped);
    thread.start();
    thread.join();

    assertThat(capturedTraceId.get()).isEqualTo("test-trace-123");
  }

  @Test
  void wrap_runnable_restoresMdcAfterExecution() {
    MDC.put("traceId", "outer-trace");
    AtomicReference<String> mdcDuringTask = new AtomicReference<>();

    MdcContext.wrap(() -> mdcDuringTask.set(MDC.get("traceId"))).run();

    // MdcContext.wrap restores the previous MDC context after the task completes
    assertThat(mdcDuringTask.get()).isEqualTo("outer-trace");
  }

  @Test
  void wrap_supplier_propagatesMdcAndReturnsValue() {
    MDC.put("traceId", "sup-trace");

    java.util.function.Supplier<String> wrapped = MdcContext.wrap(() -> MDC.get("traceId"));
    MDC.clear();
    String result = wrapped.get();

    assertThat(result).isEqualTo("sup-trace");
  }

  @Test
  void wrap_supplier_withNullMdcContext_clearsChildContext() {
    // MDC is empty (no context) — child should get clear MDC
    MDC.put("leftover", "should-be-cleared");
    MDC.clear();

    AtomicReference<String> capturedValue = new AtomicReference<>("not-set");
    Runnable wrapped = MdcContext.wrap(() -> {
      capturedValue.set(MDC.get("leftover"));
    });
    MDC.put("leftover", "parent-value"); // Put something after wrapping
    wrapped.run();

    // Wrapped task should see the context at wrap time (which was empty → cleared)
    assertThat(capturedValue.get()).isNull();
  }

  @Test
  void taskDecorator_decoratesRunnable() {
    MDC.put("taskKey", "task-value");
    TaskDecorator decorator = MdcContext.taskDecorator();
    AtomicReference<String> capturedValue = new AtomicReference<>();

    Runnable decorated = decorator.decorate(() -> capturedValue.set(MDC.get("taskKey")));
    MDC.clear();
    decorated.run();

    assertThat(capturedValue.get()).isEqualTo("task-value");
  }

  @Test
  void wrap_supplier_exceptionInTask_restoresMdc() {
    MDC.put("key", "original");

    java.util.function.Supplier<String> wrapped = MdcContext.wrap(() -> {
      throw new RuntimeException("task failed");
    });

    assertThatThrownBy(wrapped::get).isInstanceOf(RuntimeException.class);
    // MDC should be restored even after exception
    assertThat(MDC.get("key")).isEqualTo("original");
  }
}
