package com.ragforge.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.ragforge.maintenance.DataCalibrationJob;
import com.ragforge.maintenance.DataCalibrationReport;
import com.ragforge.maintenance.EsIndexRepairJob;
import com.ragforge.maintenance.EsRepairReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(MockitoExtension.class)
class MaintenanceControllerTest {

  @Mock private DataCalibrationJob dataCalibrationJob;
  @Mock private EsIndexRepairJob esIndexRepairJob;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        standaloneSetup(new MaintenanceController(dataCalibrationJob, esIndexRepairJob))
            .setMessageConverters(new MappingJackson2HttpMessageConverter())
            .build();
  }

  @Test
  void calibrate_returnsReport() throws Exception {
    DataCalibrationReport report = new DataCalibrationReport();
    report.setCheckedDocuments(3);
    when(dataCalibrationJob.calibrate()).thenReturn(report);

    mockMvc
        .perform(post("/api/v1/admin/maintenance/calibrate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.checkedDocuments").value(3));
  }

  @Test
  void repairEs_returnsReport() throws Exception {
    EsRepairReport report = new EsRepairReport();
    report.setRepairedDocuments(2);
    when(esIndexRepairJob.repairAllMissingIndexes()).thenReturn(report);

    mockMvc
        .perform(post("/api/v1/admin/maintenance/repair-es"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.repairedDocuments").value(2));
  }

  @Test
  void repairEsDocument_returnsReport() throws Exception {
    EsRepairReport report = new EsRepairReport();
    report.setCheckedDocuments(1);
    when(esIndexRepairJob.repairDocument(8L)).thenReturn(report);

    mockMvc
        .perform(post("/api/v1/admin/maintenance/repair-es/8"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.checkedDocuments").value(1));
  }
}
