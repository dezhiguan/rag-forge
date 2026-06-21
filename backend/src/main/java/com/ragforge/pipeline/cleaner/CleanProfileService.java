package com.ragforge.pipeline.cleaner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragforge.mapper.CleanProfileMapper;
import com.ragforge.model.entity.CleanProfileEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CleanProfileService {

  private final CleanProfileMapper cleanProfileMapper;
  private final ObjectMapper objectMapper;

  public ResolvedCleanProfile resolveForKb(Long kbId) {
    CleanProfileEntity entity =
        cleanProfileMapper.selectOne(
            new LambdaQueryWrapper<CleanProfileEntity>()
                .eq(CleanProfileEntity::getScope, "KB")
                .eq(CleanProfileEntity::getScopeId, kbId)
                .last("LIMIT 1"));
    if (entity == null || !StringUtils.hasText(entity.getConfig())) {
      return new ResolvedCleanProfile(null, new CleanProfile());
    }
    try {
      return new ResolvedCleanProfile(entity.getId(), objectMapper.readValue(entity.getConfig(), CleanProfile.class));
    } catch (Exception e) {
      return new ResolvedCleanProfile(entity.getId(), new CleanProfile());
    }
  }
}
