package com.ragforge.pipeline.parser;

public interface DocumentParser {

  ParseResult parse(String filePath, String fileType);
}
