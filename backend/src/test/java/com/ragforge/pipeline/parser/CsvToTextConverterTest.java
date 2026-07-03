package com.ragforge.pipeline.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.BizException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvToTextConverterTest {

  private final CsvToTextConverter conv = new CsvToTextConverter();

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  // ---------- 正常 / 序列化 ----------

  @Test
  void standard_headerPrefixedRows() {
    String out = conv.convert(utf8("姓名,部门,职级\n张三,研发,P7\n李四,产品,P6\n"));
    assertThat(out).contains("姓名: 张三").contains("部门: 研发").contains("职级: P7").contains("姓名: 李四");
    assertThat(out.split("\n\n")).hasSize(2); // 两条记录空行分隔
  }

  @Test
  void quotedComma_notSplitIntoColumns() {
    String out = conv.convert(utf8("地址,备注\n\"北京,海淀\",ok\n"));
    assertThat(out).contains("地址: 北京,海淀");
  }

  @Test
  void embeddedNewline_stayInSameRecord() {
    String out = conv.convert(utf8("col\n\"line1\nline2\"\n"));
    assertThat(out).contains("line1\nline2");
    assertThat(out.split("\n\n")).hasSize(1);
  }

  @Test
  void escapedDoubleQuote_unescaped() {
    String out = conv.convert(utf8("c\n\"他说\"\"你好\"\"\"\n"));
    assertThat(out).contains("他说\"你好\"");
  }

  @Test
  void emptyValueColumn_skipped() {
    String out = conv.convert(utf8("a,b,c\nx,,z\n"));
    assertThat(out).contains("a: x").contains("c: z").doesNotContain("b: ");
  }

  // ---------- 表头 ----------

  @Test
  void duplicateHeaders_deduped() {
    String out = conv.convert(utf8("name,name,age\nA,B,20\n"));
    assertThat(out).contains("name: A").contains("name_2: B").contains("age: 20");
  }

  @Test
  void emptyHeaderName_fallbackColumnName() {
    String out = conv.convert(utf8(",age\nx,20\n"));
    assertThat(out).contains("列1: x").contains("age: 20");
  }

  @Test
  void bomInFirstHeader_stripped() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] body = utf8("姓名,城市\n张三,北京\n");
    byte[] all = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, all, 0, bom.length);
    System.arraycopy(body, 0, all, bom.length, body.length);
    String out = conv.convert(all);
    assertThat(out).contains("姓名: 张三");
    assertThat(out).doesNotContain("﻿"); // 首列名无 BOM 残留
  }

  // ---------- 分隔符 ----------

  @Test
  void semicolonDelimiter_sniffed() {
    String out = conv.convert(utf8("a;b;c\n1;2;3\n"));
    assertThat(out).contains("a: 1").contains("b: 2").contains("c: 3");
  }

  @Test
  void tabDelimiter_sniffed() {
    String out = conv.convert(utf8("a\tb\n1\t2\n"));
    assertThat(out).contains("a: 1").contains("b: 2");
  }

  @Test
  void sniff_notMisledByValueSemicolons() {
    // 逗号 CSV，值里含 ; —— 首行嗅探不被带偏
    String out = conv.convert(utf8("a,b\nx;y,z\n"));
    assertThat(out).contains("a: x;y").contains("b: z");
  }

  @Test
  void sniffDelimiter_defaultsComma() {
    assertThat(CsvToTextConverter.sniffDelimiter("a,b,c\n1,2,3")).isEqualTo(',');
    assertThat(CsvToTextConverter.sniffDelimiter("a;b;c")).isEqualTo(';');
    assertThat(CsvToTextConverter.sniffDelimiter("single")).isEqualTo(',');
  }

  // ---------- 编码 ----------

  @Test
  void decode_utf8() {
    assertThat(CsvToTextConverter.decode(utf8("姓名,张三"))).isEqualTo("姓名,张三");
  }

  @Test
  void decode_utf8Bom_stripped() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] body = utf8("abc");
    byte[] all = new byte[bom.length + body.length];
    System.arraycopy(bom, 0, all, 0, 3);
    System.arraycopy(body, 0, all, 3, body.length);
    assertThat(CsvToTextConverter.decode(all)).isEqualTo("abc");
  }

  @Test
  void decode_gbkFallback() throws Exception {
    byte[] gbk = "姓名张三".getBytes("GB18030"); // 非法 UTF-8 → 回退 GB18030
    assertThat(CsvToTextConverter.decode(gbk)).isEqualTo("姓名张三");
  }

  @Test
  void decode_utf16LeBom() {
    byte[] body = "姓名".getBytes(StandardCharsets.UTF_16LE);
    byte[] all = new byte[2 + body.length];
    all[0] = (byte) 0xFF;
    all[1] = (byte) 0xFE;
    System.arraycopy(body, 0, all, 2, body.length);
    assertThat(CsvToTextConverter.decode(all)).isEqualTo("姓名");
  }

  @Test
  void gbkCsv_endToEnd() throws Exception {
    byte[] gbk = "姓名,城市\n张三,北京\n".getBytes("GB18030");
    String out = conv.convert(gbk);
    assertThat(out).contains("姓名: 张三").contains("城市: 北京");
  }

  // ---------- 换行 ----------

  @Test
  void crlf_noTrailingCarriageReturn() {
    String out = conv.convert(utf8("a,b\r\n1,2\r\n"));
    assertThat(out).contains("b: 2");
    assertThat(out).doesNotContain("\r");
  }

  @Test
  void blankLinesInData_skipped() {
    String out = conv.convert(utf8("a\n1\n\n2\n"));
    assertThat(out.split("\n\n")).hasSize(2);
  }

  // ---------- 列错位 ----------

  @Test
  void moreColumnsThanHeader_extraNamed() {
    String out = conv.convert(utf8("a,b\n1,2,3\n"));
    assertThat(out).contains("a: 1").contains("b: 2").contains("列3: 3");
  }

  @Test
  void fewerColumnsThanHeader_noMisalign() {
    String out = conv.convert(utf8("a,b,c\n1,2\n"));
    assertThat(out).contains("a: 1").contains("b: 2").doesNotContain("c: ");
  }

  // ---------- 值内容 ----------

  @Test
  void valueWithSemicolon_kept() {
    String out = conv.convert(utf8("note\ncontains；sep\n"));
    assertThat(out).contains("note: contains；sep");
  }

  @Test
  void numberLeadingZeros_preservedAsText() {
    String out = conv.convert(utf8("code\n0012\n"));
    assertThat(out).contains("code: 0012");
  }

  @Test
  void htmlTags_keptLiteralNotStripped() {
    String out = conv.convert(utf8("html\n<b>x</b>\n"));
    assertThat(out).contains("<b>x</b>");
  }

  // ---------- 空 / 退化 / 异常 ----------

  @Test
  void emptyBytes_throwsFriendly() {
    assertThatThrownBy(() -> conv.convert(new byte[0]))
        .isInstanceOf(BizException.class)
        .hasMessage("CSV_EMPTY");
  }

  @Test
  void onlyHeader_throwsNoDataRows() {
    assertThatThrownBy(() -> conv.convert(utf8("a,b,c\n")))
        .isInstanceOf(BizException.class)
        .hasMessage("CSV_NO_DATA_ROWS");
  }

  @Test
  void malformedQuote_throwsParseFailed() {
    assertThatThrownBy(() -> conv.convert(utf8("col\n\"a\"b\n")))
        .isInstanceOf(BizException.class)
        .hasMessage("CSV_PARSE_FAILED");
  }

  // ---------- 护栏 ----------

  @Test
  void exceedMaxRows_truncated() {
    StringBuilder sb = new StringBuilder("c\n");
    for (int i = 0; i < CsvToTextConverter.MAX_ROWS + 100; i++) {
      sb.append("v").append(i).append('\n');
    }
    String out = conv.convert(utf8(sb.toString()));
    assertThat(out.split("\n\n")).hasSize(CsvToTextConverter.MAX_ROWS);
  }
}
