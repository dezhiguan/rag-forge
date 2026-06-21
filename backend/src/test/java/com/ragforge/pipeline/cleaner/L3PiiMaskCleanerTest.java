package com.ragforge.pipeline.cleaner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ragforge.common.BizException;
import org.junit.jupiter.api.Test;

class L3PiiMaskCleanerTest {

  private final L1NormalizeCleaner l1 = new L1NormalizeCleaner();
  private final L3PiiMaskCleaner cleaner = new L3PiiMaskCleaner();

  @Test
  void clean_masksPhoneEmailIdCardAndBankCardVariants() {
    String[] phones = {
      "13800138000", "138 0013 8000", "138-0013-8000", "１３８００１３８０００", "19912345678",
      "14712345678", "158 0000 1111", "176-2222-3333", "18888888888", "13900001111",
      "13123456789", "15123456789", "17123456789", "18123456789", "19123456789",
      "133 1234 5678", "155-1234-5678", "16612345678", "17712345678", "18912345678"
    };
    for (String phone : phones) {
      String cleaned = clean(phone);
      assertThat(cleaned).containsPattern("1\\d{2}\\*{4}\\d{4}");
      assertThat(cleaned).doesNotContain(phone);
    }

    assertThat(clean("test.user+cv@example.com")).isEqualTo("t***@example.com");
    assertThat(clean("6222 0200 1111 2222")).isEqualTo("6222********2222");
    assertThat(clean("11010519491231002X")).isEqualTo("110105********002X");
  }

  @Test
  void clean_supportsHashAndRejectPolicies() {
    CleanProfile hash = new CleanProfile();
    hash.setPiiPolicy(PiiPolicy.HASH);
    String hashed = cleaner.clean(new RawText("13800138000", null, 1), hash).getCleanedText();
    assertThat(hashed).startsWith("phone#");

    CleanProfile reject = new CleanProfile();
    reject.setPiiPolicy(PiiPolicy.REJECT);
    assertThatThrownBy(() -> cleaner.clean(new RawText("a@b.com", null, 1), reject))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("PII_REJECTED");
  }

  @Test
  void clean_doesNotMaskInvalidIdCardChecksum() {
    assertThat(clean("110105194912310021")).isEqualTo("110105194912310021");
  }

  private String clean(String input) {
    CleanProfile profile = new CleanProfile();
    String normalized = l1.clean(new RawText(input, null, 1), profile).getCleanedText();
    return cleaner.clean(new RawText(normalized, null, 1), profile).getCleanedText();
  }
}
