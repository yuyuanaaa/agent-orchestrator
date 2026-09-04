package com.agentorchestrator.platform.agent.router;

import com.agentorchestrator.platform.skill.Skill;
import org.springframework.ai.document.Document;

import java.util.List;

public record RouteDecisionTotal(
        RouteDecision decision,
        List<Skill> skills
){}
