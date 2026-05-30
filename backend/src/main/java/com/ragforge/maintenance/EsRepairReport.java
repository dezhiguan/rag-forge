package com.ragforge.maintenance;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class EsRepairReport {

  private int checkedDocuments;
  private int repairedDocuments;
  private int skippedDocuments;
  private int failedDocuments;
  private long elapsedMs;
  private List<Item> items = new ArrayList<>();

  public void addItem(Long docId, String filename, int pgChunks, long esChunksBefore, String status, String message) {
    Item item = new Item();
    item.setDocId(docId);
    item.setFilename(filename);
    item.setPgChunks(pgChunks);
    item.setEsChunksBefore(esChunksBefore);
    item.setStatus(status);
    item.setMessage(message);
    items.add(item);
  }

  @Data
  public static class Item {
    private Long docId;
    private String filename;
    private int pgChunks;
    private long esChunksBefore;
    private String status;
    private String message;
  }
}
