package com.ragforge.bootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ragforge.mapper.ApiKeyMapper;
import com.ragforge.model.entity.ApiKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

@ExtendWith(MockitoExtension.class)
class DataInitRunnerTest {

  @Mock private ApiKeyMapper apiKeyMapper;

  @InjectMocks private DataInitRunner runner;

  @Test
  void run_devKeyAlreadyExists_doesNotInsert() throws Exception {
    when(apiKeyMapper.selectCount(any())).thenReturn(1L);

    runner.run(new DefaultApplicationArguments());

    verify(apiKeyMapper, never()).insert(any(ApiKey.class));
  }

  @Test
  void run_devKeyMissing_insertsDevKey() throws Exception {
    when(apiKeyMapper.selectCount(any())).thenReturn(0L);

    runner.run(new DefaultApplicationArguments());

    verify(apiKeyMapper).insert(any(ApiKey.class));
  }

  @Test
  void run_nullCount_insertsDevKey() throws Exception {
    when(apiKeyMapper.selectCount(any())).thenReturn(null);

    runner.run(new DefaultApplicationArguments());

    verify(apiKeyMapper).insert(any(ApiKey.class));
  }
}
