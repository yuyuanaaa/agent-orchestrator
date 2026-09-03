package com.kanodays88.skytakeoutai.skill;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Skill 注册中心 —— 管理所有已加载的业务技能，提供查找和上下文生成能力。
 * <p>
 * 在应用启动时通过 {@link SkillLoader#loadAllSkills()} 加载
 * {@code classpath:skills/**\/*.md} 下的所有技能定义，缓存到内存中。
 * <p>
 * 架构中的两处使用点：
 * <ul>
 *   <li><b>RouterAgent</b>：调用 {@link #findRelevant(String)} 匹配用户输入对应的 Skill，
 *       结合 Skill 的必需参数判断用户信息是否充分 → 决定是否 AMBIGUOUS</li>
 *   <li><b>PlanExecute</b>：调用 {@link #getMatchingSkillsPromptContext(String)} 生成
 *       匹配 Skill 的 Markdown 上下文文本 → 注入 LLM system prompt 指导任务分解</li>
 * </ul>
 */
@Component
@Slf4j
public class SkillRegistry {

    private final SkillLoader skillLoader;

    /** 所有已加载的 Skill 列表 */
    @Getter
    private List<Skill> allSkills = new ArrayList<>();

    /** Skill 名称 → Skill 的映射，用于快速精确查找 */
    private Map<String, Skill> skillMap = new HashMap<>();

    public SkillRegistry(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /** 应用启动时自动加载所有 Skill 文件 */
    @PostConstruct
    public void init() {
        allSkills = skillLoader.loadAllSkills();
        skillMap = allSkills.stream()
                .collect(Collectors.toMap(Skill::getName, s -> s, (a, b) -> a));
        log.info("SkillRegistry initialized with {} skills", allSkills.size());
        if (allSkills.isEmpty()) {
            log.warn("No skills loaded. Check resources/skills/ directory.");
        }
    }

    /** 根据技能名称精确查找 */
    public Skill getSkill(String name) {
        return skillMap.get(name);
    }

    /** 根据业务领域查找第一个匹配的技能 */
    public Skill getSkillByDomain(String domain) {
        return allSkills.stream()
                .filter(s -> domain.equals(s.getDomain()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据用户输入查询匹配的 Skill 列表，按相关度降序排列。
     * <p>
     * 匹配维度（按权重从高到低）：
     * <ol>
     *   <li>用户输入包含 Skill 名称</li>
     *   <li>用户输入包含 Skill 所属领域名</li>
     *   <li>用户输入中的关键词与 Skill 描述匹配</li>
     *   <li>用户输入中的关键词与 Skill 示例匹配</li>
     *   <li>用户输入包含 Skill 参数名</li>
     * </ol>
     * 如果没有匹配到任何 Skill，返回空列表。
     */
    public List<Skill> findRelevant(String query) {
        if (query == null || query.isBlank()) return new ArrayList<>(allSkills);

        String lowerQuery = query.toLowerCase();
        Set<Skill> matched = new LinkedHashSet<>();

        for (Skill skill : allSkills) {
            double score = calculateRelevance(skill, lowerQuery);
            if (score > 0) {
                matched.add(skill);
            }
        }

        if (matched.isEmpty()) {
            return new ArrayList<>();
        }

        List<Skill> sorted = new ArrayList<>(matched);
        sorted.sort((a, b) -> Double.compare(
                calculateRelevance(b, lowerQuery),
                calculateRelevance(a, lowerQuery)));
        return sorted;
    }

    /** 将所有 Skill 格式化为 LLM prompt 上下文文本 */
    public String getSkillsPromptContext() {
        return allSkills.stream()
                .map(Skill::toPromptContext)
                .collect(Collectors.joining("\n---\n"));
    }

    /** 将指定的 Skill 列表格式化为 LLM prompt 上下文文本 */
    public String getSkillsPromptContext(List<Skill> skills) {
        return skills.stream()
                .map(Skill::toPromptContext)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 根据用户输入生成匹配 Skill 的 prompt 上下文文本。
     * 先通过 {@link #findRelevant(String)} 匹配，然后将匹配到的 Skill
     * 格式化为 Markdown 文本，用于注入 LLM 的 system prompt。
     * <p>
     * 输出格式：先列出匹配到的 Skills 概览，再给出每个 Skill 的详细定义。
     */
    public String getMatchingSkillsPromptContext(String query) {
        List<Skill> relevant = findRelevant(query);
        if (relevant.isEmpty()) return "当前没有匹配的业务技能。";
        return "当前可用的业务技能：\n" + relevant.stream()
                .map(s -> "- " + s.getName() + ": " + s.getDescription())
                .collect(Collectors.joining("\n")) + "\n\n详细定义：\n" + getSkillsPromptContext(relevant);
    }

    /**
     * 按需加载指定技能的参考文档内容 —— 执行层（Execution）的核心入口。
     * <p>
     * 对应 Anthropic Agent Skill 标准第三层「执行层」：仅在 Agent 执行过程中
     * 引用对应资源时才加载文件内容，最大化节约上下文 Token。
     *
     * @param skillName     技能名称
     * @param referenceFile 参考文档路径（如 "references/api-spec.md"）
     * @return 文件文本内容，加载失败返回 null
     */
    public String getReferenceContent(String skillName, String referenceFile) {
        Skill skill = getSkill(skillName);
        if (skill == null || referenceFile == null) return null;
        return skillLoader.loadReferenceContent(skill, referenceFile);
    }

    /**
     * 计算单个 Skill 与用户查询的相关度分数。
     * 多维度加权求和，权重从高到低：名称匹配 &gt; 领域匹配 &gt; 描述关键词 &gt; 示例关键词 &gt; 参数名。
     */
    private double calculateRelevance(Skill skill, String lowerQuery) {
        double score = 0.0;

        if (skill.getName() != null && lowerQuery.contains(skill.getName().toLowerCase())) {
            score += 1.0;
        }

        if (skill.getDomain() != null && lowerQuery.contains(skill.getDomain().toLowerCase())) {
            score += 0.8;
        }

        // 查询词元：英文按空白切分，中文切成二元组（bigram），
        // 否则「我想点个菜」这类整句中文永远匹配不上任何关键词
        List<String> queryTokens = tokenize(lowerQuery);

        if (skill.getDescription() != null) {
            String lowerDesc = skill.getDescription().toLowerCase();
            for (String token : queryTokens) {
                if (lowerDesc.contains(token)) {
                    score += 0.3;
                }
            }
        }

        if (skill.getExamples() != null) {
            for (String ex : skill.getExamples()) {
                String lowerEx = ex.toLowerCase();
                long matchCount = queryTokens.stream()
                        .filter(lowerEx::contains)
                        .count();
                score += matchCount * 0.2;
            }
        }

        if (skill.getAllParamNames() != null) {
            for (String paramName : skill.getAllParamNames()) {
                if (lowerQuery.contains(paramName.toLowerCase())) {
                    score += 0.1;
                }
            }
        }

        return score;
    }

    /**
     * 中英混合分词：
     * <ul>
     *   <li>英文 / 数字：按空白与标点切分成完整单词</li>
     *   <li>中文（CJK）连续段：切成长度为 2 的滑窗二元组，
     *       例如「查订单」→ [查订, 订单]，与「订单」这类关键词即可命中</li>
     * </ul>
     * 丢弃长度小于 2 的碎片（单字歧义太大，会引入大量误匹配）。
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cjkBuffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkBuffer.append(c);
            } else {
                flushCjk(cjkBuffer, tokens);
                if (Character.isLetterOrDigit(c)) {
                    int start = i;
                    while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
                        i++;
                    }
                    String word = text.substring(start, i);
                    if (word.length() > 1) {
                        tokens.add(word.toLowerCase());
                    }
                    i--;
                }
            }
        }
        flushCjk(cjkBuffer, tokens);
        return tokens;
    }

    /** 把累积的 CJK 段切成二元组 */
    private void flushCjk(StringBuilder cjkBuffer, List<String> tokens) {
        if (cjkBuffer.length() == 0) return;
        String s = cjkBuffer.toString();
        for (int j = 0; j + 1 < s.length(); j++) {
            tokens.add(s.substring(j, j + 2));
        }
        cjkBuffer.setLength(0);
    }

    private boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
