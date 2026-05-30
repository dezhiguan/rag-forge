package com.ragforge.maintenance;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class DataCalibrationReport {

  private int checkedDocuments;
  private int fixedDocuments;
  private int documentsMissingVector;
  private int documentsStatusMismatch;
  private int checkedKnowledgeBases;
  private int fixedKnowledgeBases;
  private long elapsedMs;
  private List<Issue> issues = new ArrayList<>();

  public void addIssue(String type, Long kbId, Long docId, String message) {
    Issue issue = new Issue();
    issue.setType(type);
    issue.setKbId(kbId);
    issue.setDocId(docId);
    issue.setMessage(message);
    issues.add(issue);
  }

  @Data
  public static class Issue {
    private String type;
    private Long kbId;
    private Long docId;
    private String message;
  }
}
