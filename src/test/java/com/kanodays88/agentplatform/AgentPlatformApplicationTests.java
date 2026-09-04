package com.kanodays88.agentplatform;

import com.kanodays88.agentplatform.constant.FileConstant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 上下文加载冒烟测试。
 * <p>
 * 注意：需要本地 MySQL / Redis / 模型 API Key 就绪后才能通过，
 * CI 中建议通过 profile 或环境变量提供依赖，或跳过本测试。
 */
@SpringBootTest
class AgentPlatformApplicationTests {

    @Test
    void contextLoads() {
        // 文件根目录可配置，打印出来便于核对启动环境
        System.out.println("FILE_SAVE_DIR = " + FileConstant.FILE_SAVE_DIR);
    }
}
