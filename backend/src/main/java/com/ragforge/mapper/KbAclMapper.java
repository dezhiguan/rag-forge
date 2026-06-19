package com.ragforge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragforge.model.entity.KbAcl;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KbAclMapper extends BaseMapper<KbAcl> {

  @Select({
    "<script>",
    "SELECT EXISTS (",
    "  SELECT 1 FROM kb_acl",
    "  WHERE principal_type = 'user'",
    "    AND principal_id = CAST(#{userId} AS VARCHAR)",
    "    AND kb_id = #{kbId}",
    "    AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)",
    "    AND permission IN",
    "    <foreach collection='permissions' item='permission' open='(' separator=',' close=')'>",
    "      #{permission}",
    "    </foreach>",
    ")",
    "</script>"
  })
  boolean existsByUserAndKb(
      @Param("userId") Long userId,
      @Param("kbId") Long kbId,
      @Param("permissions") Collection<String> permissions);

  @Select("""
      SELECT DISTINCT kb_id
      FROM kb_acl
      WHERE principal_type = 'user'
        AND principal_id = CAST(#{userId} AS VARCHAR)
        AND permission IN ('read', 'write', 'admin')
        AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
      ORDER BY kb_id
      """)
  List<Long> findReadableKbIds(@Param("userId") Long userId);

  @Select("""
      SELECT DISTINCT kb_id
      FROM kb_acl
      WHERE principal_type = 'user'
        AND principal_id = CAST(#{userId} AS VARCHAR)
        AND permission IN ('write', 'admin')
        AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
      ORDER BY kb_id
      """)
  List<Long> findWritableKbIds(@Param("userId") Long userId);

  @Select("""
      SELECT DISTINCT kb_id
      FROM kb_acl
      WHERE principal_type = 'user'
        AND principal_id = CAST(#{userId} AS VARCHAR)
        AND permission = 'admin'
        AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
      ORDER BY kb_id
      """)
  List<Long> findAdminKbIds(@Param("userId") Long userId);
}
