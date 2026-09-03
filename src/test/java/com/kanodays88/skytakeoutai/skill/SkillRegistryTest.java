package com.kanodays88.skytakeoutai.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillRegistry 中文匹配单元测试（不依赖 Spring 容器与外部服务）。
 * <p>
 * 背景：原实现用 split("\\s+") 对查询分词，中文整句永远切不出关键词，
 * 导致「帮我点个外卖」这类纯中文输入匹配不到任何技能。
 */
class SkillRegistryTest {

    private static SkillRegistry registry;

    @BeforeAll
    static void init() {
        // SkillLoader 基于 classpath 扫描，可独立于 Spring 容器实例化
        registry = new SkillRegistry(new SkillLoader());
        registry.init();
    }

    @Test
    @DisplayName("中文整句输入应能匹配到相关技能")
    void chineseQueryShouldMatchSkills() {
        List<Skill> relevant = registry.findRelevant("帮我查一下有什么便宜的菜品");
        assertFalse(relevant.isEmpty(), "纯中文查询不应返回空结果");
    }

    @Test
    @DisplayName("中文查询应优先匹配语义最相关的技能")
    void chineseQueryShouldRankRelevantSkillFirst() {
        List<Skill> relevant = registry.findRelevant("我想取消刚才下的订单");
        assertFalse(relevant.isEmpty());
        assertTrue(relevant.get(0).getName().toLowerCase().contains("cancel")
                        || relevant.get(0).getName().toLowerCase().contains("order"),
                "排名第一的应是订单相关技能，实际是: " + relevant.get(0).getName());
    }

    @Test
    @DisplayName("中英混合输入（含技能英文名）依然可以匹配")
    void mixedQueryShouldStillMatch() {
        // 技能 name 是英文（如 cancel_order），查询中直接出现技能名时应命中
        List<Skill> relevant = registry.findRelevant("cancel_order 怎么用");
        assertFalse(relevant.isEmpty());
        assertTrue(relevant.stream().anyMatch(s -> "cancel_order".equals(s.getName())),
                "包含技能名的查询应命中该技能");
    }
}
