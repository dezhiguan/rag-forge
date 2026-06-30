package com.ragforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityResolverTest {

  private final CapabilityResolver resolver = new CapabilityResolver();

  @Test
  void capabilitiesFor_adminRole_includesBaseAndAdminExtras() {
    List<String> caps = resolver.capabilitiesFor("ADMIN");
    assertThat(caps).contains("dashboard:read", "kb:read", "search:run", "answer:run");
    assertThat(caps).contains("eval:write", "perf:read", "quality:read", "cost:read", "apikey:admin", "platform:admin");
  }

  @Test
  void capabilitiesFor_kbEditorRole_includesBaseAndEvalWrite() {
    List<String> caps = resolver.capabilitiesFor("KB_EDITOR");
    assertThat(caps).contains("dashboard:read", "kb:read", "search:run", "eval:write");
    assertThat(caps).doesNotContain("platform:admin", "cost:read");
  }

  @Test
  void capabilitiesFor_kbViewerRole_includesOnlyBase() {
    List<String> caps = resolver.capabilitiesFor("KB_VIEWER");
    assertThat(caps).contains("dashboard:read", "kb:read", "search:run");
    assertThat(caps).doesNotContain("eval:write", "platform:admin");
  }

  @Test
  void capabilitiesFor_unknownRole_returnsBaseCapabilities() {
    List<String> caps = resolver.capabilitiesFor("SOME_UNKNOWN_ROLE");
    assertThat(caps).contains("dashboard:read");
    assertThat(caps).doesNotContain("platform:admin");
  }

  @Test
  void capabilitiesFor_nullRole_returnsBaseCapabilities() {
    List<String> caps = resolver.capabilitiesFor(null);
    assertThat(caps).contains("dashboard:read", "search:run");
    assertThat(caps).doesNotContain("platform:admin");
  }

  @Test
  void capabilitiesFor_caseInsensitive_adminLowercase() {
    List<String> caps = resolver.capabilitiesFor("admin");
    assertThat(caps).contains("platform:admin");
  }

  @Test
  void isAdmin_adminRole_returnsTrue() {
    assertThat(resolver.isAdmin("ADMIN")).isTrue();
    assertThat(resolver.isAdmin("admin")).isTrue();
    assertThat(resolver.isAdmin("Admin")).isTrue();
  }

  @Test
  void isAdmin_nonAdminRoles_returnsFalse() {
    assertThat(resolver.isAdmin("KB_EDITOR")).isFalse();
    assertThat(resolver.isAdmin("KB_VIEWER")).isFalse();
    assertThat(resolver.isAdmin("USER")).isFalse();
    assertThat(resolver.isAdmin(null)).isFalse();
  }

  @Test
  void capabilitiesFor_noDuplicates() {
    List<String> caps = resolver.capabilitiesFor("ADMIN");
    assertThat(caps).doesNotHaveDuplicates();
  }
}
