package com.ragforge.keel;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 给 Keel 共享服务页的只读摘要。不返回检索原文和文档内容。 */
@Service
public class KeelInsightService {

  private final JdbcTemplate jdbc;

  public KeelInsightService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> snapshot() {
    Window current = window("created_at >= now() - interval '24 hours'");
    Window previous =
        window(
            "created_at >= now() - interval '48 hours' AND created_at < now() - interval '24 hours'");
    var counts = countsByKb("created_at >= now() - interval '24 hours'");
    Double cost =
        jdbc.queryForObject(
            "SELECT coalesce(sum(cost), 0) FROM model_usage_daily WHERE stat_date = current_date",
            Double.class);
    List<Kb> bases =
        jdbc.query(
            """
            SELECT kb.id, kb.name, org.name AS owner, kb.doc_count, kb.updated_at,
                   kb.updated_at < now() - interval '30 days' AS stale
            FROM knowledge_bases kb
            LEFT JOIN organizations org ON org.id = kb.org_id
            WHERE kb.status IS DISTINCT FROM 'deleted'
            ORDER BY kb.name
            """,
            (rs, row) ->
                new Kb(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getString("owner"),
                    rs.getObject("doc_count") == null ? null : rs.getInt("doc_count"),
                    iso(rs.getTimestamp("updated_at")),
                    rs.getBoolean("stale")));
    return assemble(current, previous, bases, counts, cost);
  }

  private Window window(String predicate) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status = 'SUCCESS') AS success,
                   count(*) FILTER (WHERE status = 'SUCCESS' AND result_count = 0) AS zero,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
                     FILTER (WHERE status = 'SUCCESS') AS p50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
                     FILTER (WHERE status = 'SUCCESS') AS p95
            FROM retrieval_logs
            WHERE %s
            """
                .formatted(predicate));
    return new Window(
        number(row.get("total")),
        number(row.get("success")),
        number(row.get("zero")),
        decimal(row.get("p50")),
        decimal(row.get("p95")));
  }

  private Map<Long, long[]> countsByKb(String predicate) {
    var counts = new LinkedHashMap<Long, long[]>();
    jdbc.query(
        """
        SELECT kb_ids, result_count
        FROM retrieval_logs
        WHERE %s AND status = 'SUCCESS' AND kb_ids IS NOT NULL AND kb_ids <> ''
        """
            .formatted(predicate),
        rs -> {
          while (rs.next()) {
            int resultCount = rs.getInt("result_count");
            for (String part : rs.getString("kb_ids").split(",")) {
              String trimmed = part.trim();
              if (trimmed.isEmpty()) {
                continue;
              }
              long id;
              try {
                id = Long.parseLong(trimmed);
              } catch (NumberFormatException ex) {
                continue;
              }
              long[] stat = counts.computeIfAbsent(id, key -> new long[2]);
              stat[0]++;
              if (resultCount == 0) {
                stat[1]++;
              }
            }
          }
          return null;
        });
    return counts;
  }

  static Map<String, Object> assemble(
      Window current, Window previous, List<Kb> bases, Map<Long, long[]> counts, Double cost) {
    var body = new LinkedHashMap<String, Object>();
    body.put("searches24h", current.total());
    body.put("searchesPrev24h", previous.total());
    body.put("p50Ms", current.success() > 0 ? current.p50Ms() : null);
    body.put("p95Ms", current.success() > 0 ? current.p95Ms() : null);
    body.put(
        "zeroResultRate",
        current.success() > 0 ? (double) current.zero() / current.success() : null);
    body.put("modelCostCny", cost);
    var rows = new ArrayList<Map<String, Object>>();
    for (Kb kb : bases) {
      long[] stat = counts.getOrDefault(kb.id(), new long[2]);
      var row = new LinkedHashMap<String, Object>();
      row.put("kb", kb.name());
      row.put("owner", kb.owner());
      row.put("documents", kb.documents());
      row.put("updatedAt", kb.updatedAt());
      row.put("searches24h", stat[0]);
      row.put("zeroHitRate", stat[0] > 0 ? (double) stat[1] / stat[0] : null);
      row.put("recallAt5", null);
      row.put("stale", kb.stale());
      rows.add(row);
    }
    body.put("knowledgeBases", rows);
    return body;
  }

  private static long number(Object value) {
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static Double decimal(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private static String iso(Timestamp timestamp) {
    if (timestamp == null) {
      return null;
    }
    return timestamp.toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  record Window(long total, long success, long zero, Double p50Ms, Double p95Ms) {}

  record Kb(long id, String name, String owner, Integer documents, String updatedAt, boolean stale) {}
}
