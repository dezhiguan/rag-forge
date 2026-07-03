package com.ragforge.pipeline.parser;

import com.ragforge.common.BizException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * 把 CSV 字节流转成「行即 chunk + 表头前缀」的可读文本，交给现有文本分块管道。
 *
 * <p>处理真实世界脏数据：编码检测(UTF-8/UTF-16 BOM、GBK 回退)、分隔符嗅探(<code>,</code>/<code>;</code>/<code>\t</code>)、
 * 表头重名/空列名兜底、列数错位、行数/字符护栏。序列化为每行 <code>列名: 值；列名: 值</code>，行间空行分隔。
 * 空文件 / 仅表头 / 解析失败均抛出可被 {@code ErrorMessages.toUserFriendly} 映射的友好错误码。
 */
@Slf4j
@Component
public class CsvToTextConverter {

  /** 行数上限，超出截断并告警(避免超大表拖垮 worker)。 */
  static final int MAX_ROWS = 50_000;
  /** 输出字符上限，超出截断。 */
  static final int MAX_OUTPUT_CHARS = 8_000_000;
  /** 分隔符嗅探候选（按优先级）。 */
  private static final char[] DELIMS = {',', ';', '\t'};
  /** 字段分隔(全角分号，与半角内容冲突概率低)。 */
  private static final String FIELD_SEP = "；";
  private static final String RECORD_SEP = "\n\n";

  /** CSV 字节 → 行文本；失败抛友好错误码。 */
  public String convert(byte[] bytes) {
    String text = decode(bytes);
    char delimiter = sniffDelimiter(text);
    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setDelimiter(delimiter)
            .setIgnoreEmptyLines(true)
            .setAllowMissingColumnNames(true)
            .build();

    try (CSVParser parser = CSVParser.parse(text, format)) {
      var it = parser.iterator();
      if (!it.hasNext()) {
        throw new BizException("CSV_EMPTY");
      }
      List<String> headers = normalizeHeaders(it.next());

      StringBuilder sb = new StringBuilder();
      int included = 0;
      boolean truncated = false;
      while (it.hasNext()) {
        if (included >= MAX_ROWS || sb.length() >= MAX_OUTPUT_CHARS) {
          truncated = true;
          break;
        }
        String row = serializeRow(headers, it.next());
        if (row.isEmpty()) {
          continue; // 整行空值，跳过
        }
        if (included > 0) {
          sb.append(RECORD_SEP);
        }
        sb.append(row);
        included++;
      }

      if (included == 0) {
        throw new BizException("CSV_NO_DATA_ROWS");
      }
      if (truncated) {
        log.warn("CSV 超过上限已截断：仅入库前 {} 行(MAX_ROWS={}, MAX_CHARS={})", included, MAX_ROWS, MAX_OUTPUT_CHARS);
      }
      return sb.toString();
    } catch (BizException e) {
      throw e;
    } catch (Exception e) {
      log.warn("CSV 解析失败", e);
      throw new BizException("CSV_PARSE_FAILED");
    }
  }

  /** 编码检测：BOM 优先(UTF-8/UTF-16)，无 BOM 时先严格 UTF-8，失败回退 GB18030(覆盖中文 Excel 的 GBK/GB2312)。 */
  static String decode(byte[] raw) {
    if (raw == null || raw.length == 0) {
      throw new BizException("CSV_EMPTY");
    }
    if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
      return new String(raw, 3, raw.length - 3, StandardCharsets.UTF_8); // UTF-8 BOM
    }
    if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
      return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
    }
    if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
      return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
    }
    try {
      CharsetDecoder decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return decoder.decode(ByteBuffer.wrap(raw)).toString();
    } catch (CharacterCodingException notUtf8) {
      for (String name : new String[] {"GB18030", "GBK", "GB2312"}) {
        if (Charset.isSupported(name)) {
          return new String(raw, Charset.forName(name)); // 中文 Excel 默认 GBK
        }
      }
      return new String(raw, StandardCharsets.UTF_8);
    }
  }

  /** 用首行嗅探分隔符：逗号默认，分号/Tab 更多则取之(用表头行避免被数据值里的分隔符带偏)。 */
  static char sniffDelimiter(String text) {
    int nl = text.indexOf('\n');
    String firstLine = nl >= 0 ? text.substring(0, nl) : text;
    char best = ',';
    int bestCount = count(firstLine, ',');
    for (char d : DELIMS) {
      if (d == ',') {
        continue;
      }
      int c = count(firstLine, d);
      if (c > bestCount) {
        bestCount = c;
        best = d;
      }
    }
    return best;
  }

  private static int count(String s, char c) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == c) {
        n++;
      }
    }
    return n;
  }

  /** 表头归一：空列名兜底 <code>列N</code>，重名追加 <code>_2/_3</code>，避免序列化歧义。 */
  private static List<String> normalizeHeaders(CSVRecord header) {
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < header.size(); i++) {
      String h = header.get(i);
      h = h == null ? "" : h.strip();
      if (h.isEmpty()) {
        h = "列" + (i + 1);
      }
      String base = h;
      int n = 2;
      while (seen.contains(h)) {
        h = base + "_" + n++;
      }
      seen.add(h);
      out.add(h);
    }
    return out;
  }

  /** 一行 → <code>列名: 值；列名: 值</code>；跳过空值列；列数与表头不齐时兜底列名/留空不错位。 */
  private static String serializeRow(List<String> headers, CSVRecord record) {
    int cols = Math.max(headers.size(), record.size());
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < cols; i++) {
      String h = i < headers.size() ? headers.get(i) : ("列" + (i + 1));
      String v = i < record.size() ? record.get(i) : "";
      v = v == null ? "" : v.strip(); // strip 顺带去掉 CRLF 残留的 \r
      if (v.isEmpty()) {
        continue;
      }
      parts.add(h + ": " + v);
    }
    return String.join(FIELD_SEP, parts);
  }
}
