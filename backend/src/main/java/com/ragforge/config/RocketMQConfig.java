package com.ragforge.config;

import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Ensures RocketMQ auto-configuration loads on Spring Boot 3.x
 * (requires rocketmq-spring-boot-starter 2.3+ and rocketmq.name-server + producer.group).
 */
@Configuration
@Import(RocketMQAutoConfiguration.class)
public class RocketMQConfig {}
