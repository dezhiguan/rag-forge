package com.ragforge.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessProducer {

  public static final String TOPIC = "ragforge-document-process";

  private final RocketMQTemplate rocketMQTemplate;

  public void send(Long documentId) {
    rocketMQTemplate.convertAndSend(TOPIC, documentId);
    log.info("Sent document process message: docId={}", documentId);
  }
}
