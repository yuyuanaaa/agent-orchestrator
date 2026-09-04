package com.kanodays88.agentplatform.agent.router;

import com.kanodays88.agentplatform.skill.Skill;
import org.springframework.ai.document.Document;

import java.util.List;

public record RouteDecisionTotal(
        RouteDecision decision,
        List<Skill> skills
){}
