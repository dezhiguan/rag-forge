package com.ragforge.service.ingest;

import com.ragforge.model.dto.IngestCommand;
import com.ragforge.model.dto.IngestResult;

public interface IngestService {

  IngestResult register(IngestCommand cmd);
}
