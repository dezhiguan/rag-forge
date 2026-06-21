package com.ragforge.tools;

import com.ragforge.pipeline.chunker.Chunk;
import com.ragforge.pipeline.chunker.TextChunker;
import com.ragforge.pipeline.cleaner.CleanProfile;
import com.ragforge.pipeline.cleaner.CleanResult;
import com.ragforge.pipeline.cleaner.CleaningPipeline;
import com.ragforge.pipeline.cleaner.PiiPatterns;
import com.ragforge.pipeline.cleaner.RawText;
import com.ragforge.pipeline.parser.DocumentParser;
import com.ragforge.pipeline.parser.ParseResult;
import com.ragforge.storage.ObjectStorage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("acceptance")
@RequiredArgsConstructor
public class CleanerAcceptanceRunner implements ApplicationRunner {

  private static final int PDF_LIMIT = 30;
  private static final int MARKDOWN_LIMIT = 10;
  private static final int HTML_LIMIT = 10;
  private static final int REPORT_SAMPLE_CHARS = 1200;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectStorage objectStorage;
  private final DocumentParser documentParser;
  private final TextChunker textChunker;
  private final CleaningPipeline cleaningPipeline;
  private final ConfigurableApplicationContext applicationContext;

  @Value("${storage.aliyun.bucket:}")
  private String defaultBucket;

  @Override
  public void run(ApplicationArguments args) throws Exception {
    List<DocCandidate> docs = loadCandidates();
    log.info("Cleaner acceptance dry-run selected {} documents", docs.size());

    List<DocMetrics> metrics = new ArrayList<>();
    for (DocCandidate doc : docs) {
      try {
        metrics.add(runOne(doc));
      } catch (Exception e) {
        DocMetrics failed = new DocMetrics();
        failed.setDoc(doc);
        failed.setError(e.getMessage());
        metrics.add(failed);
        log.warn("Cleaner acceptance failed for docId={}: {}", doc.getId(), e.getMessage());
      }
    }

    Path report = Path.of("target", "cleaner-acceptance-report.html");
    Files.createDirectories(report.getParent());
    Files.writeString(report, buildReport(metrics));
    Summary summary = summarize(metrics);
    log.info(
        "Cleaner acceptance report generated: {} (重复率下降 {}%, PII 脱敏率 {}%)",
        report.toAbsolutePath(),
        fmt(summary.getDuplicateDropPercent()),
        fmt(summary.getPiiFullyMaskedDocPercent()));
    SpringApplication.exit(applicationContext, () -> 0);
    System.exit(0);
  }

  private List<DocCandidate> loadCandidates() {
    String sql =
        """
        WITH pdf AS (
          SELECT id, filename, file_type, file_size, storage_bucket, file_path
          FROM documents
          WHERE file_path IS NOT NULL
            AND (lower(coalesce(file_type, '')) IN ('pdf', 'application/pdf') OR lower(filename) LIKE '%%.pdf')
            AND upper(coalesce(parse_status, '')) = 'COMPLETED'
          ORDER BY CASE WHEN coalesce(file_size, 0) > 512000 THEN 0 ELSE 1 END, coalesce(file_size, 0) DESC, id DESC
          LIMIT ?
        ),
        markdown AS (
          SELECT id, filename, file_type, file_size, storage_bucket, file_path
          FROM documents
          WHERE file_path IS NOT NULL
            AND (lower(coalesce(file_type, '')) IN ('md', 'markdown', 'text/markdown') OR lower(filename) LIKE '%%.md')
            AND upper(coalesce(parse_status, '')) = 'COMPLETED'
          ORDER BY id DESC
          LIMIT ?
        ),
        html AS (
          SELECT id, filename, file_type, file_size, storage_bucket, file_path
          FROM documents
          WHERE file_path IS NOT NULL
            AND (lower(coalesce(file_type, '')) IN ('html', 'htm', 'text/html') OR lower(filename) LIKE '%%.html' OR lower(filename) LIKE '%%.htm')
            AND upper(coalesce(parse_status, '')) = 'COMPLETED'
          ORDER BY CASE WHEN coalesce(ingest_source, '') ILIKE '%%boss%%' THEN 0 ELSE 1 END, id DESC
          LIMIT ?
        )
        SELECT * FROM pdf
        UNION ALL SELECT * FROM markdown
        UNION ALL SELECT * FROM html
        """;
    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> {
          DocCandidate doc = new DocCandidate();
          doc.setId(rs.getLong("id"));
          doc.setFilename(rs.getString("filename"));
          doc.setFileType(rs.getString("file_type"));
          doc.setFileSize(rs.getLong("file_size"));
          doc.setBucket(firstNonBlank(rs.getString("storage_bucket"), defaultBucket));
          doc.setStorageKey(rs.getString("file_path"));
          return doc;
        },
        PDF_LIMIT,
        MARKDOWN_LIMIT,
        HTML_LIMIT);
  }

  private DocMetrics runOne(DocCandidate doc) throws Exception {
    Path temp = Files.createTempFile("ragforge-cleaner-" + doc.getId() + "-", suffix(doc));

    try {
      SourceText source = loadSourceText(doc, temp);
      String rawText = source.getText();
      List<Chunk> chunksA = textChunker.chunk(rawText);

      CleanResult cleaned =
          cleaningPipeline.clean(
              new RawText(rawText, contentType(doc), source.getPageCount()), new CleanProfile());
      String cleanedText = cleaned.getCleanedText() == null ? "" : cleaned.getCleanedText();
      List<Chunk> chunksB = textChunker.chunk(cleanedText);

      DocMetrics m = new DocMetrics();
      m.setDoc(doc);
      m.setSource(source.getSource());
      m.setRawText(rawText);
      m.setCleanedText(cleanedText);
      m.setChunkCountA(chunksA.size());
      m.setChunkCountB(chunksB.size());
      m.setDuplicateRateA(duplicateRate(chunksA));
      m.setDuplicateRateB(duplicateRate(chunksB));
      m.setPiiA(countPii(chunksA));
      m.setPiiB(countPii(chunksB));
      m.setAverageLengthA(averageLength(chunksA));
      m.setAverageLengthB(averageLength(chunksB));
      m.setCleanPiiHits(cleaned.getPiiHits() == null ? Map.of() : cleaned.getPiiHits());
      return m;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private SourceText loadSourceText(DocCandidate doc, Path temp) throws Exception {
    Path localPath = Path.of(doc.getStorageKey());
    if (localPath.isAbsolute() && Files.isRegularFile(localPath)) {
      Files.copy(localPath, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      ParseResult parsed = documentParser.parse(temp.toString(), parserType(doc));
      return new SourceText(nullToEmpty(parsed.getText()), parsed.getPageCount(), "LOCAL_FILE");
    }
    try {
      try (InputStream in = objectStorage.get(doc.getBucket(), doc.getStorageKey())) {
        if (in == null) {
          throw new IllegalStateException("objectStorage.get returned null");
        }
        Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      ParseResult parsed = documentParser.parse(temp.toString(), parserType(doc));
      return new SourceText(nullToEmpty(parsed.getText()), parsed.getPageCount(), "OBJECT_STORAGE");
    } catch (Exception objectError) {
      String fallback = loadChunkFallback(doc.getId());
      if (!fallback.isBlank()) {
        return new SourceText(fallback, 1, "PG_CHUNKS_FALLBACK");
      }
      throw objectError;
    }
  }

  private String loadChunkFallback(Long docId) {
    String sql =
        "select string_agg(content, E'\\n\\n' order by chunk_index) from document_chunks where doc_id = ?";
    String text = jdbcTemplate.queryForObject(sql, String.class, docId);
    return nullToEmpty(text);
  }

  private double duplicateRate(List<Chunk> chunks) {
    if (chunks == null || chunks.size() < 2) {
      return 0.0;
    }
    int duplicatePairs = 0;
    for (int i = 1; i < chunks.size(); i++) {
      double similarity = jaccard(chunks.get(i - 1).getContent(), chunks.get(i).getContent());
      if (similarity > 0.95) {
        duplicatePairs++;
      }
    }
    return duplicatePairs * 1.0 / (chunks.size() - 1);
  }

  private double jaccard(String left, String right) {
    Set<String> a = ngrams(normalizeForCompare(left), 5);
    Set<String> b = ngrams(normalizeForCompare(right), 5);
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }
    int intersection = 0;
    for (String token : a) {
      if (b.contains(token)) {
        intersection++;
      }
    }
    int union = a.size() + b.size() - intersection;
    return union == 0 ? 0.0 : intersection * 1.0 / union;
  }

  private Set<String> ngrams(String text, int n) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    if (text.length() <= n) {
      return Set.of(text);
    }
    Set<String> grams = new LinkedHashSet<>();
    for (int i = 0; i <= text.length() - n; i++) {
      grams.add(text.substring(i, i + n));
    }
    return grams;
  }

  private PiiCount countPii(List<Chunk> chunks) {
    PiiCount count = new PiiCount();
    if (chunks == null) {
      return count;
    }
    for (Chunk chunk : chunks) {
      String text = Normalizer.normalize(nullToEmpty(chunk.getContent()), Normalizer.Form.NFKC);
      count.setPhone(count.getPhone() + count(PiiPatterns.PHONE, text));
      count.setEmail(count.getEmail() + count(PiiPatterns.EMAIL, text));
      count.setIdCard(count.getIdCard() + count(PiiPatterns.ID_CARD, text));
      count.setBankCard(count.getBankCard() + count(PiiPatterns.BANK_CARD, text));
    }
    return count;
  }

  private int count(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }

  private double averageLength(List<Chunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return 0.0;
    }
    return chunks.stream().map(Chunk::getContent).filter(Objects::nonNull).mapToInt(String::length).average().orElse(0.0);
  }

  private String buildReport(List<DocMetrics> metrics) {
    Summary summary = summarize(metrics);
    List<DocMetrics> valid = metrics.stream().filter(DocMetrics::isValid).toList();
    List<DocMetrics> worst =
        valid.stream()
            .sorted(Comparator.comparingDouble(DocMetrics::getDuplicateRateB).reversed())
            .limit(3)
            .toList();

    StringBuilder html = new StringBuilder(64_000);
    html.append(
        """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <title>RAGForge T8 Cleaner Acceptance Report</title>
          <style>
            body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #172033; background: #f5f7fb; }
            main { max-width: 1280px; margin: 0 auto; padding: 32px 24px 56px; }
            h1 { margin: 0 0 6px; font-size: 28px; }
            h2 { margin: 32px 0 14px; font-size: 18px; }
            .muted { color: #637083; }
            .summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 20px; }
            .metric { background: #fff; border: 1px solid #dbe3ef; border-radius: 8px; padding: 14px; }
            .metric strong { display: block; font-size: 24px; margin-top: 8px; }
            .ok { color: #138a52; }
            .warn { color: #b25b00; }
            table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #dbe3ef; border-radius: 8px; overflow: hidden; }
            th, td { padding: 10px 12px; border-bottom: 1px solid #e7edf5; text-align: left; vertical-align: top; font-size: 13px; }
            th { background: #edf2f8; color: #344154; font-weight: 700; }
            tr:last-child td { border-bottom: 0; }
            .case { background: #fff; border: 1px solid #dbe3ef; border-radius: 8px; margin-bottom: 16px; overflow: hidden; }
            .case header { display:flex; justify-content:space-between; gap:16px; padding: 12px 14px; background:#edf2f8; }
            .compare { display: grid; grid-template-columns: 1fr 1fr; gap: 1px; background:#dbe3ef; }
            pre { margin:0; padding:14px; white-space: pre-wrap; word-break: break-word; background:#fff; min-height: 220px; max-height: 360px; overflow:auto; font-size: 12px; line-height: 1.55; }
            mark { background:#fff0b8; padding: 0 2px; }
            .badge { display:inline-block; padding: 2px 8px; border-radius:999px; background:#edf2f8; }
            .bad { color:#b42318; font-weight:700; }
          </style>
        </head>
        <body><main>
        """);
    html.append("<h1>T8 Cleaner Acceptance Dry-Run</h1>");
    html.append("<div class=\"muted\">生成时间：").append(escape(LocalDateTime.now().toString())).append("</div>");
    html.append("<section class=\"summary\">");
    metric(html, "文档数", valid.size() + " / " + metrics.size(), true);
    metric(html, "总体重复率下降", fmt(summary.getDuplicateDropPercent()) + "%", summary.getDuplicateDropPercent() >= 40.0);
    metric(html, "PII 完全脱敏 doc 占比", fmt(summary.getPiiFullyMaskedDocPercent()) + "%", summary.getPiiFullyMaskedDocPercent() >= 95.0);
    metric(html, "chunk 数变化", signed(summary.getChunkCountChangePercent()) + "%", true);
    html.append("</section>");

    html.append("<h2>50 个 doc 明细</h2><table><thead><tr>");
    html.append("<th>docId</th><th>文件</th><th>类型</th><th>A 重复率</th><th>B 重复率</th><th>PII A -> B</th><th>chunk A -> B</th><th>均长 A -> B</th><th>来源/状态</th>");
    html.append("</tr></thead><tbody>");
    for (DocMetrics m : metrics) {
      html.append("<tr>");
      html.append("<td>").append(m.getDoc() == null ? "" : m.getDoc().getId()).append("</td>");
      html.append("<td>").append(escape(m.getDoc() == null ? "" : m.getDoc().getFilename())).append("</td>");
      html.append("<td>").append(escape(m.getDoc() == null ? "" : m.getDoc().getFileType())).append("</td>");
      if (m.isValid()) {
        html.append("<td>").append(fmt(percent(m.getDuplicateRateA()))).append("%</td>");
        html.append("<td>").append(fmt(percent(m.getDuplicateRateB()))).append("%</td>");
        html.append("<td>").append(m.getPiiA().total()).append(" -> ").append(m.getPiiB().total()).append("</td>");
        html.append("<td>").append(m.getChunkCountA()).append(" -> ").append(m.getChunkCountB()).append("</td>");
        html.append("<td>").append(fmt(m.getAverageLengthA())).append(" -> ").append(fmt(m.getAverageLengthB())).append("</td>");
        html.append("<td><span class=\"badge\">").append(escape(m.getSource())).append("</span></td>");
      } else {
        html.append("<td colspan=\"6\" class=\"bad\">").append(escape(m.getError())).append("</td>");
        html.append("<td><span class=\"badge\">FAILED</span></td>");
      }
      html.append("</tr>");
    }
    html.append("</tbody></table>");

    html.append("<h2>3 个 worst case 截图式对比</h2>");
    for (DocMetrics m : worst) {
      html.append("<section class=\"case\"><header>");
      html.append("<strong>docId=").append(m.getDoc().getId()).append(" · ").append(escape(m.getDoc().getFilename())).append("</strong>");
      html.append("<span>B 重复率 ").append(fmt(percent(m.getDuplicateRateB()))).append("%，PII ")
          .append(m.getPiiA().total()).append(" -> ").append(m.getPiiB().total()).append("</span>");
      html.append("</header><div class=\"compare\"><pre>")
          .append(escape(sample(m.getRawText())))
          .append("</pre><pre>")
          .append(highlightCleaned(sample(m.getRawText()), sample(m.getCleanedText())))
          .append("</pre></div></section>");
    }

    html.append("<h2>结论</h2><p>");
    if (summary.getDuplicateDropPercent() < 40.0 || summary.getPiiFullyMaskedDocPercent() < 95.0) {
      html.append("指标未完全达标。优先查看 worst case：若 PDF 重复率仍高，通常是 L2 页眉页脚/目录识别不够强；若 PII 未清零，通常是 L3 正则对分隔符、全角或业务格式覆盖不足。");
    } else {
      html.append("指标达到验收阈值。");
    }
    html.append("</p></main></body></html>");
    return html.toString();
  }

  private Summary summarize(List<DocMetrics> metrics) {
    List<DocMetrics> valid = metrics.stream().filter(DocMetrics::isValid).toList();
    Summary summary = new Summary();
    if (valid.isEmpty()) {
      return summary;
    }
    int pairsA = valid.stream().mapToInt(DocMetrics::adjacentPairCountA).sum();
    int pairsB = valid.stream().mapToInt(DocMetrics::adjacentPairCountB).sum();
    double dupA =
        valid.stream().mapToDouble(m -> m.getDuplicateRateA() * m.adjacentPairCountA()).sum()
            / Math.max(1, pairsA);
    double dupB =
        valid.stream().mapToDouble(m -> m.getDuplicateRateB() * m.adjacentPairCountB()).sum()
            / Math.max(1, pairsB);
    int chunkA = valid.stream().mapToInt(DocMetrics::getChunkCountA).sum();
    int chunkB = valid.stream().mapToInt(DocMetrics::getChunkCountB).sum();
    long piiFullyMaskedDocs =
        valid.stream().filter(m -> m.getPiiA().total() == 0 || m.getPiiB().total() == 0).count();

    summary.setDuplicateRateA(dupA);
    summary.setDuplicateRateB(dupB);
    summary.setDuplicateDropPercent(dupA == 0.0 ? 0.0 : Math.max(0.0, (dupA - dupB) * 100.0 / dupA));
    summary.setPiiFullyMaskedDocPercent(piiFullyMaskedDocs * 100.0 / valid.size());
    summary.setChunkCountChangePercent(chunkA == 0 ? 0.0 : (chunkB - chunkA) * 100.0 / chunkA);
    return summary;
  }

  private void metric(StringBuilder html, String label, String value, boolean ok) {
    html.append("<div class=\"metric\"><span>")
        .append(escape(label))
        .append("</span><strong class=\"")
        .append(ok ? "ok" : "warn")
        .append("\">")
        .append(escape(value))
        .append("</strong></div>");
  }

  private String parserType(DocCandidate doc) {
    String type = nullToEmpty(doc.getFileType()).toLowerCase(Locale.ROOT);
    if (type.contains("pdf")) {
      return "pdf";
    }
    if (type.contains("markdown") || type.equals("md")) {
      return "md";
    }
    if (type.contains("html") || type.equals("htm")) {
      return "html";
    }
    String filename = nullToEmpty(doc.getFilename()).toLowerCase(Locale.ROOT);
    if (filename.endsWith(".pdf")) {
      return "pdf";
    }
    if (filename.endsWith(".md") || filename.endsWith(".markdown")) {
      return "md";
    }
    if (filename.endsWith(".html") || filename.endsWith(".htm")) {
      return "html";
    }
    return type;
  }

  private String contentType(DocCandidate doc) {
    return switch (parserType(doc)) {
      case "pdf" -> "application/pdf";
      case "md" -> "text/markdown";
      case "html" -> "text/html";
      default -> doc.getFileType();
    };
  }

  private String suffix(DocCandidate doc) {
    return "." + switch (parserType(doc)) {
      case "pdf" -> "pdf";
      case "md" -> "md";
      case "html" -> "html";
      default -> "bin";
    };
  }

  private String normalizeForCompare(String text) {
    return Normalizer.normalize(nullToEmpty(text), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", "");
  }

  private String sample(String text) {
    String value = nullToEmpty(text);
    return value.length() <= REPORT_SAMPLE_CHARS ? value : value.substring(0, REPORT_SAMPLE_CHARS);
  }

  private String highlightCleaned(String raw, String cleaned) {
    String escaped = escape(cleaned);
    if (!Objects.equals(normalizeForCompare(raw), normalizeForCompare(cleaned))) {
      return "<mark>" + escaped + "</mark>";
    }
    return escaped;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String signed(double value) {
    return (value > 0 ? "+" : "") + fmt(value);
  }

  private static double percent(double value) {
    return value * 100.0;
  }

  private static String fmt(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private static String escape(String value) {
    return nullToEmpty(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  @Data
  private static class DocCandidate {
    private Long id;
    private String filename;
    private String fileType;
    private Long fileSize;
    private String bucket;
    private String storageKey;
  }

  @Data
  private static class DocMetrics {
    private DocCandidate doc;
    private String source;
    private String rawText;
    private String cleanedText;
    private int chunkCountA;
    private int chunkCountB;
    private double duplicateRateA;
    private double duplicateRateB;
    private PiiCount piiA = new PiiCount();
    private PiiCount piiB = new PiiCount();
    private double averageLengthA;
    private double averageLengthB;
    private Map<String, Integer> cleanPiiHits = new LinkedHashMap<>();
    private String error;

    boolean isValid() {
      return error == null || error.isBlank();
    }

    int adjacentPairCountA() {
      return Math.max(0, chunkCountA - 1);
    }

    int adjacentPairCountB() {
      return Math.max(0, chunkCountB - 1);
    }
  }

  @Data
  private static class SourceText {
    private final String text;
    private final int pageCount;
    private final String source;
  }

  @Data
  private static class PiiCount {
    private int phone;
    private int email;
    private int idCard;
    private int bankCard;

    int total() {
      return phone + email + idCard + bankCard;
    }
  }

  @Data
  private static class Summary {
    private double duplicateRateA;
    private double duplicateRateB;
    private double duplicateDropPercent;
    private double piiFullyMaskedDocPercent;
    private double chunkCountChangePercent;
  }
}
