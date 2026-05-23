package com.example.demo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String DOC_EXCHANGE = "document.process.exchange";
    public static final String DOC_QUEUE = "document.process.queue";
    public static final String DOC_ROUTING_KEY = "document.process";

    public static final String DOC_RETRY_EXCHANGE = "document.process.retry.exchange";
    public static final String DOC_RETRY_QUEUE = "document.process.retry.queue";
    public static final String DOC_RETRY_ROUTING_KEY = "document.process.retry";

    public static final String DOC_DEAD_EXCHANGE = "document.process.dead.exchange";
    public static final String DOC_DEAD_QUEUE = "document.process.dead.queue";
    public static final String DOC_DEAD_ROUTING_KEY = "document.process.dead";

    @Bean
    public DirectExchange docExchange() {
        return new DirectExchange(DOC_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange docRetryExchange() {
        return new DirectExchange(DOC_RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange docDeadExchange() {
        return new DirectExchange(DOC_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue docQueue() {
        return QueueBuilder.durable(DOC_QUEUE).build();
    }

    @Bean
    public Queue docRetryQueue(@Value("${document.processing.retry-delay-ms:30000}") long retryDelayMs) {
        return QueueBuilder.durable(DOC_RETRY_QUEUE)
                .deadLetterExchange(DOC_EXCHANGE)
                .deadLetterRoutingKey(DOC_ROUTING_KEY)
                .ttl((int) Math.max(1000, Math.min(retryDelayMs, Integer.MAX_VALUE)))
                .build();
    }

    @Bean
    public Queue docDeadQueue() {
        return QueueBuilder.durable(DOC_DEAD_QUEUE).build();
    }

    @Bean
    public Binding docBinding(@Qualifier("docQueue") Queue docQueue,
                              @Qualifier("docExchange") DirectExchange docExchange) {
        return BindingBuilder.bind(docQueue).to(docExchange).with(DOC_ROUTING_KEY);
    }

    @Bean
    public Binding docRetryBinding(@Qualifier("docRetryQueue") Queue docRetryQueue,
                                   @Qualifier("docRetryExchange") DirectExchange docRetryExchange) {
        return BindingBuilder.bind(docRetryQueue).to(docRetryExchange).with(DOC_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding docDeadBinding(@Qualifier("docDeadQueue") Queue docDeadQueue,
                                  @Qualifier("docDeadExchange") DirectExchange docDeadExchange) {
        return BindingBuilder.bind(docDeadQueue).to(docDeadExchange).with(DOC_DEAD_ROUTING_KEY);
    }
}
