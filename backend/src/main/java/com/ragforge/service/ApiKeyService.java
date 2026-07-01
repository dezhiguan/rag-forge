package com.ragforge.service;

import com.ragforge.model.dto.CreateApiKeyCommand;
import com.ragforge.model.entity.ApiKey;
import java.util.List;

public interface ApiKeyService {

  /** 当前组织的 key（方案 B：平台视图不再浏览全量，返回空）。 */
  List<ApiKey> listForCurrentOrg();

  /** 定向治理查询（仅超管破玻璃，按名称/前缀精确定位）。 */
  List<ApiKey> governanceSearch(String q);

  /** 定向吊销（仅超管破玻璃，强制原因 + 审计）。 */
  ApiKey revokeWithReason(Long id, String reason);

  /** 创建 key（组织 OWNER/ADMIN；范围/级别/过期见命令）。 */
  ApiKey create(CreateApiKeyCommand cmd);

  /** 重命名 key（仅所属组织 admin）。 */
  ApiKey rename(Long id, String keyName);

  ApiKey enable(Long id, boolean enabled);

  void delete(Long id);
}
