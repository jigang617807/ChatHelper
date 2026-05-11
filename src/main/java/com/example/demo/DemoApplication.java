package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.example.demo", "com.example.agent"})
@EntityScan(basePackages = {"com.example.demo.entity", "com.example.agent.entity"})
@EnableJpaRepositories(basePackages = {"com.example.demo.repository", "com.example.agent.repository"})
@EnableElasticsearchRepositories(basePackages = "com.example.demo.search")
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
