package com.ragforge.tools;

import com.ragforge.mq.DocumentProcessProducer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("rebuild")
@RequiredArgsConstructor
public class FullRebuildRunner implements ApplicationRunner {

  private static final int THROTTLE_BATCH_SIZE = 100;
  private static final long THROTTLE_SLEEP_MS = 1000L;

  private final JdbcTemplate jdbcTemplate;
  private final DocumentProcessProducer mqProducer;
  private final ConfigurableApplicationContext applicationContext;

  @Override
  public void run(ApplicationArguments args) throws Exception {
    List<Long> documentIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM documents WHERE parse_status = 'PENDING' ORDER BY id", Long.class);
    int submitted = 0;
    for (Long documentId : documentIds) {
      mqProducer.send(documentId);
      submitted++;
      if (submitted % THROTTLE_BATCH_SIZE == 0 && submitted < documentIds.size()) {
        Thread.sleep(THROTTLE_SLEEP_MS);
      }
    }
    log.info("FullRebuild submitted {} docs", submitted);
    closeApplicationContextAsync();
  }

  private void closeApplicationContextAsync() {
    Thread shutdownThread = new Thread(applicationContext::close, "ragforge-rebuild-shutdown");
    shutdownThread.setDaemon(false);
    shutdownThread.start();
  }
}
