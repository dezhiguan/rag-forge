package com.ragforge.modelcenter;

import com.ragforge.mapper.ModelConfigMapper;
import com.ragforge.model.entity.ModelConfig;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 运行时模型解析：用途 → 当前生效模型。替换散落在代码/yml 的硬编码模型名。
 *
 * <p>解析顺序：enabled 的 primary → primary 的 fallback_code（若 enabled）→ 该用途任一 enabled 模型 → 抛
 * {@link ModelUnavailableException}。
 *
 * <p>全量缓存在内存，{@link #refresh()} 在 toggle 写库后由调用方主动刷新（单实例；多实例广播失效留 TODO）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelResolver {

  private final ModelConfigMapper modelConfigMapper;

  private volatile Map<String, ModelConfig> byCode = Map.of();
  private volatile Map<Purpose, List<ModelConfig>> byPurpose = Map.of();

  @PostConstruct
  public void refresh() {
    List<ModelConfig> all = modelConfigMapper.selectList(null);
    this.byCode = all.stream().collect(Collectors.toUnmodifiableMap(ModelConfig::getCode, Function.identity()));
    this.byPurpose =
        all.stream()
            .filter(c -> parsePurpose(c.getPurpose()) != null)
            .collect(Collectors.groupingBy(c -> parsePurpose(c.getPurpose())));
    log.info("ModelResolver cache refreshed: {} models, {} purposes", all.size(), byPurpose.size());
  }

  /** 解析用途对应的生效模型；无可用模型抛 {@link ModelUnavailableException}。 */
  public ModelConfig resolve(Purpose purpose) {
    List<ModelConfig> list = byPurpose.getOrDefault(purpose, List.of());

    Optional<ModelConfig> primary = list.stream().filter(c -> Boolean.TRUE.equals(c.getIsPrimary())).findFirst();
    if (primary.isPresent() && Boolean.TRUE.equals(primary.get().getEnabled())) {
      return primary.get();
    }
    if (primary.isPresent() && primary.get().getFallbackCode() != null) {
      ModelConfig fb = byCode.get(primary.get().getFallbackCode());
      if (fb != null && Boolean.TRUE.equals(fb.getEnabled())) {
        return fb;
      }
    }
    return list.stream()
        .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
        .findFirst()
        .orElseThrow(() -> new ModelUnavailableException(purpose));
  }

  /** 解析模型 code；无可用模型时返回给定默认值（用于可优雅降级的非关键链路，如改写）。 */
  public String resolveCodeOrDefault(Purpose purpose, String defaultCode) {
    try {
      return resolve(purpose).getCode();
    } catch (ModelUnavailableException e) {
      return defaultCode;
    }
  }

  /** 校验：停用 code 后，其用途是否仍有可解析的模型（供 toggle 守卫使用）。 */
  public boolean purposeStillResolvableWithout(Purpose purpose, String disablingCode) {
    return byPurpose.getOrDefault(purpose, List.of()).stream()
        .anyMatch(c -> Boolean.TRUE.equals(c.getEnabled()) && !c.getCode().equals(disablingCode));
  }

  private static Purpose parsePurpose(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Purpose.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
