package com.ragforge.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ragforge.model.entity.KbAcl;
import com.ragforge.model.entity.KnowledgeBase;
import java.time.LocalDateTime;
import java.util.List;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    classes = KbAclMapperTest.MapperTestConfig.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:kb_acl_mapper_test;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.profiles.active=test",
      "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
      "spring.flyway.enabled=false",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:kb-acl-mapper-schema.sql",
      "mybatis-plus.configuration.map-underscore-to-camel-case=true"
    })
@Transactional
class KbAclMapperTest {

  @Autowired private KnowledgeBaseMapper knowledgeBaseMapper;

  @Autowired private KbAclMapper kbAclMapper;

  @Test
  void existsByUserAndKbHonorsPermissionAndExpiry() {
    KnowledgeBase readable = insertKb("acl-readable");
    KnowledgeBase writable = insertKb("acl-writable");
    KnowledgeBase expired = insertKb("acl-expired");
    long userId = 26001L;

    insertAcl(readable.getId(), userId, "read", null);
    insertAcl(writable.getId(), userId, "admin", null);
    insertAcl(expired.getId(), userId, "write", LocalDateTime.now().minusMinutes(1));

    assertThat(kbAclMapper.existsByUserAndKb(userId, readable.getId(), List.of("read"))).isTrue();
    assertThat(kbAclMapper.existsByUserAndKb(userId, writable.getId(), List.of("write", "admin"))).isTrue();
    assertThat(kbAclMapper.existsByUserAndKb(userId, expired.getId(), List.of("write", "admin"))).isFalse();
  }

  @Test
  void findReadableAndWritableKbIdsUsePermissionGroups() {
    KnowledgeBase readOnly = insertKb("acl-read-only");
    KnowledgeBase write = insertKb("acl-write");
    KnowledgeBase admin = insertKb("acl-admin");
    KnowledgeBase otherUser = insertKb("acl-other-user");
    long userId = 26002L;

    insertAcl(readOnly.getId(), userId, "read", null);
    insertAcl(write.getId(), userId, "write", null);
    insertAcl(admin.getId(), userId, "admin", null);
    insertAcl(otherUser.getId(), 26003L, "admin", null);

    assertThat(kbAclMapper.findReadableKbIds(userId))
        .contains(readOnly.getId(), write.getId(), admin.getId())
        .doesNotContain(otherUser.getId());
    assertThat(kbAclMapper.findWritableKbIds(userId))
        .contains(write.getId(), admin.getId())
        .doesNotContain(readOnly.getId(), otherUser.getId());
  }

  private KnowledgeBase insertKb(String prefix) {
    LocalDateTime now = LocalDateTime.now();
    KnowledgeBase kb = new KnowledgeBase();
    kb.setName(prefix + "-" + System.nanoTime());
    kb.setDescription("acl mapper test");
    kb.setEmbeddingModel("text-embedding-v4");
    kb.setChunkSize(512);
    kb.setChunkOverlap(64);
    kb.setDocCount(0);
    kb.setChunkCount(0);
    kb.setStatus("active");
    kb.setTenantId("tn_test_acl");
    kb.setOwnerUserId(26000L);
    kb.setVisibility("PRIVATE");
    kb.setKbType("GENERAL");
    kb.setCreatedAt(now);
    kb.setUpdatedAt(now);
    knowledgeBaseMapper.insert(kb);
    return kb;
  }

  private void insertAcl(Long kbId, long userId, String permission, LocalDateTime expiresAt) {
    LocalDateTime now = LocalDateTime.now();
    KbAcl acl = new KbAcl();
    acl.setKbId(kbId);
    acl.setPrincipalType("user");
    acl.setPrincipalId(String.valueOf(userId));
    acl.setPermission(permission);
    acl.setExpiresAt(expiresAt);
    acl.setCreatedAt(now);
    acl.setUpdatedAt(now);
    kbAclMapper.insert(acl);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @MapperScan("com.ragforge.mapper")
  static class MapperTestConfig {}
}
