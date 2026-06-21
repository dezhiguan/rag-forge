package com.ragforge.tools;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mq.DocumentProcessProducer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class FullRebuildRunnerTest {

  @Test
  void run_sendsPendingDocsInOrderAndClosesContext() throws Exception {
    JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    DocumentProcessProducer producer = org.mockito.Mockito.mock(DocumentProcessProducer.class);
    ConfigurableApplicationContext context =
        org.mockito.Mockito.mock(ConfigurableApplicationContext.class);
    when(jdbcTemplate.queryForList(
            "SELECT id FROM documents WHERE parse_status = 'PENDING' ORDER BY id", Long.class))
        .thenReturn(List.of(3L, 5L, 9L));

    FullRebuildRunner runner = new FullRebuildRunner(jdbcTemplate, producer, context);
    runner.run(null);

    InOrder inOrder = inOrder(producer);
    inOrder.verify(producer).send(3L);
    inOrder.verify(producer).send(5L);
    inOrder.verify(producer).send(9L);
    verify(context, org.mockito.Mockito.timeout(1000)).close();
  }
}
