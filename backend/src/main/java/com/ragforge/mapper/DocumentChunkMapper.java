package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.DocumentChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

  @Delete("DELETE FROM document_chunks WHERE doc_id = #{docId}")
  int deleteByDocumentId(@Param("docId") Long docId);
}
