package com.agentorchestrator.platform.benchmark;

import com.agentorchestrator.platform.skill.Skill;
import com.agentorchestrator.platform.skill.SkillLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 基准测试：三层技能加载的 Token 节省。
 * <p>
 * 对比两种加载策略：
 * <ul>
 *   <li>【当前实现】路由阶段只注入元数据层（name / domain / description）</li>
 *   <li>【反例 baseline】一次性注入完整 SKILL.md 正文</li>
 * </ul>
 * Token 估算：中英文混合按 1 token ≈ 2 chars 近似（中文字符密度高于 ASCII），
 * 同时给出 1 token ≈ 4 chars 的保守估计（与 GPT 风格 BPE 接近）。
 * <p>
 * 运行：mvnw test -Dtest=SkillTokenBenchmark
 */
class SkillTokenBenchmark {

    /** 中文为主的 Markdown，1 token ≈ 2 chars（经验值，与 Qwen tokenizer 实测接近） */
    private static final double CHARS_PER_TOKEN_DENSE = 2.0;
    /** 保守估计：GPT 风格 BPE 对中文也按字分词，约 1 token ≈ 4 chars */
    private static final double CHARS_PER_TOKEN_CONSERVATIVE = 4.0;

    @Test
    @DisplayName("三层技能加载：元数据层 vs 全量正文 Token 节省比例")
    void measureSkillLoadingTokenSavings() {
        SkillLoader loader = new SkillLoader();
        List<Skill> skills = loader.loadAllSkills();

        long totalFullChars = 0;       // 反例：注入完整 SKILL.md
        long totalMetadataChars = 0;   // 当前实现：只注入元数据层
        long totalDefinitionChars = 0; // 命中后注入定义层（不含 references/）

        System.out.println("===== Skill Token 节省基准 =====");
        System.out.printf("%-30s  %10s  %10s  %10s%n", "Skill", "FullChars", "Metadata", "Definition");
        System.out.println("-".repeat(70));

        for (Skill s : skills) {
            String full = s.getRawContent() == null ? "" : s.getRawContent();
            String metadata = String.format("name: %s\ndomain: %s\ndescription: %s",
                    s.getName(),
                    s.getDomain() == null ? "" : s.getDomain(),
                    s.getDescription() == null ? "" : s.getDescription());
            // 定义层 = 完整 SKILL.md 正文（去掉 YAML frontmatter）
            String definition = full.replaceFirst("^---.*?---\\s*\\n", "");

            totalFullChars += full.length();
            totalMetadataChars += metadata.length();
            totalDefinitionChars += definition.length();

            System.out.printf("%-30s  %10d  %10d  %10d%n",
                    truncate(s.getName(), 30),
                    full.length(),
                    metadata.length(),
                    definition.length());
        }

        System.out.println("-".repeat(70));
        System.out.printf("%-30s  %10d  %10d  %10d%n", "TOTAL",
                totalFullChars, totalMetadataChars, totalDefinitionChars);
        System.out.println();

        // 路由阶段节省：元数据 vs 全量
        double savedRatio = 1.0 - ((double) totalMetadataChars / totalFullChars);
        long denseSavedTokens = (long) ((totalFullChars - totalMetadataChars) / CHARS_PER_TOKEN_DENSE);
        long conservativeSavedTokens = (long) ((totalFullChars - totalMetadataChars) / CHARS_PER_TOKEN_CONSERVATIVE);

        System.out.printf("【路由阶段】元数据 vs 全量正文 节省 %.1f%% 字符%n", savedRatio * 100);
        System.out.printf("【路由阶段】按 1tok≈2chars 估算，每次路由节省约 %,d tokens%n", denseSavedTokens);
        System.out.printf("【路由阶段】按 1tok≈4chars 估算，每次路由节省约 %,d tokens%n", conservativeSavedTokens);

        // 命中后：定义层 vs 全量
        double defSavedRatio = 1.0 - ((double) totalDefinitionChars / totalFullChars);
        System.out.printf("【命中后】定义层（不含 YAML）vs 全量正文 节省 %.1f%% 字符%n", defSavedRatio * 100);
        System.out.println("===== END =====");
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
