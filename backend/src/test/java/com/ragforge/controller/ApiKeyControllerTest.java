package com.ragforge.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.mapper.OrganizationMapper;
import com.ragforge.model.dto.CreateApiKeyCommand;
import com.ragforge.model.entity.ApiKey;
import com.ragforge.service.ApiKeyService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class ApiKeyControllerTest {

  @Mock private ApiKeyService apiKeyService;
  @Mock private OrganizationMapper organizationMapper;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new ApiKeyController(apiKeyService, organizationMapper))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void listAll_returnsApiKeys() throws Exception {
    ApiKey key = new ApiKey();
    key.setId(1L);
    key.setKeyName("ci-key");
    key.setApiKey("sk-rf-secret");
    when(apiKeyService.listForCurrentOrg()).thenReturn(List.of(key));

    mockMvc
        .perform(get("/api/v1/keys"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].keyName").value("ci-key"))
        .andExpect(jsonPath("$.data[0].apiKey").doesNotExist());
  }

  @Test
  void create_returnsNewApiKey() throws Exception {
    ApiKey key = new ApiKey();
    key.setId(2L);
    key.setKeyName("new-key");
    key.setApiKey("sk-rf-new");
    when(apiKeyService.create(any(CreateApiKeyCommand.class))).thenReturn(key);

    mockMvc
        .perform(
            post("/api/v1/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyName\":\"new-key\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.keyName").value("new-key"))
        .andExpect(jsonPath("$.data.apiKey").value("sk-rf-new"));
  }

  @Test
  void enable_updatesApiKeyStatus() throws Exception {
    ApiKey key = new ApiKey();
    key.setId(3L);
    key.setEnabled(false);
    key.setApiKey("sk-rf-secret");
    when(apiKeyService.enable(3L, false)).thenReturn(key);

    mockMvc
        .perform(
            put("/api/v1/keys/3/enable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.apiKey").doesNotExist());
  }

  @Test
  void delete_delegatesToService() throws Exception {
    mockMvc.perform(delete("/api/v1/keys/4")).andExpect(status().isOk());

    verify(apiKeyService).delete(4L);
  }
}
