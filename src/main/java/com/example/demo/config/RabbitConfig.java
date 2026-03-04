package com.example.demo.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String DOC_QUEUE = "document.process.queue";

    @Bean
    public Queue docQueue() {
        return new Queue(DOC_QUEUE, true); // true = 持久化队列
    }
}
