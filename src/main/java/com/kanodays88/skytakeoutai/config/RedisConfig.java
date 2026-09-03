package com.kanodays88.skytakeoutai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Value("${spring.redis.database}")
    private int redisDatabase;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        config.setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.setPassword(redisPassword);
        }
        log.info("初始化 Redis 连接: host={}, port={}, database={}", redisHost, redisPort, redisDatabase);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}