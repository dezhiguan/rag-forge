package com.ragforge.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

class SchedulerLockConfigurationTest {

  @Test
  void dataCalibrationScheduledMethodIsLocked() throws NoSuchMethodException {
    Method m = DataCalibrationJob.class.getMethod("calibrateCounters");
    SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
    assertThat(lock).isNotNull();
    assertThat(lock.name()).isEqualTo("DataCalibrationJob_calibrateCounters");
  }

  @Test
  void esIndexRepairScheduledMethodIsLocked() throws NoSuchMethodException {
    Method m = EsIndexRepairJob.class.getMethod("repairMissingIndexes");
    SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
    assertThat(lock).isNotNull();
    assertThat(lock.name()).isEqualTo("EsIndexRepairJob_repairMissingIndexes");
  }
}
