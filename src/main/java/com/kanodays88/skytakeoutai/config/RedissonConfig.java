package com.kanodays88.skytakeoutai.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置。
 * <p>
 * 连接信息与 {@link RedisConfig} 保持同一套配置源，避免使用两套不一致的 Redis 地址。
 */
@Configuration
@Slf4j
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Value("${spring.redis.database}")
    private int redisDatabase;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        org.redisson.config.SingleServerConfig singleServerConfig = config
                .useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isBlank()) {
            singleServerConfig.setPassword(redisPassword);
        }
        log.info("初始化 Redisson 连接: address={}, database={}", address, redisDatabase);
        return Redisson.create(config);
    }
}
