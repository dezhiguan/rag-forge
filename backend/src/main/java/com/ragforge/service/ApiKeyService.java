package com.ragforge.service;

import com.ragforge.model.entity.ApiKey;
import java.util.List;

public interface ApiKeyService {

  List<ApiKey> listAll();

  ApiKey create(String keyName);

  ApiKey enable(Long id, boolean enabled);

  void delete(Long id);
}
