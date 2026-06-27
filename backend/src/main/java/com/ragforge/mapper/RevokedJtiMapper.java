package com.ragforge.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RevokedJtiMapper {

  @Insert(
      """
      INSERT INTO revoked_jtis (jti, expires_at, source)
      VALUES (#{jti}, #{expiresAt}, #{source})
      ON CONFLICT (jti) DO NOTHING
      """)
  void revoke(@Param("jti") String jti, @Param("expiresAt") Instant expiresAt, @Param("source") String source);

  @Select(
      """
      SELECT EXISTS (
        SELECT 1
        FROM revoked_jtis
        WHERE jti = #{jti}
          AND expires_at > CURRENT_TIMESTAMP
      )
      """)
  boolean isRevoked(@Param("jti") String jti);
}
