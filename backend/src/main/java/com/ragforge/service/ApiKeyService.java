package com.ragforge.service;

import com.ragforge.model.entity.ApiKey;
import java.util.List;

public interface ApiKeyService {

  /** 当前组织的 key；超管破玻璃(全平台视图)时返回全部（治理）。 */
  List<ApiKey> listForCurrentOrg();

  ApiKey create(String keyName);

  ApiKey enable(Long id, boolean enabled);

  void delete(Long id);
}
