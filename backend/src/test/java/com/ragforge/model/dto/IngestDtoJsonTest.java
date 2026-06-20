package com.ragforge.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IngestDtoJsonTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void onConflictSerializesAndDeserializesAllValues() throws Exception {
    for (OnConflict value : OnConflict.values()) {
      String json = objectMapper.writeValueAsString(value);

      assertThat(objectMapper.readValue(json, OnConflict.class)).isEqualTo(value);
    }

    assertThat(Arrays.stream(OnConflict.values()).map(Enum::name))
        .containsExactly("REJECT", "SKIP", "REPLACE");
  }

  @Test
  void ingestCommandDefaultsOnConflictToReject() {
    IngestCommand command = new IngestCommand();

    assertThat(command.getOnConflict()).isEqualTo(OnConflict.REJECT);
  }
}
