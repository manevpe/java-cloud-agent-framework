package io.github.manevpe.agentic.workflow;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evaluates a {@link TriggerDefinition#condition()} SpEL expression (e.g.
 * {@code "labels.contains('ready-for-dev')"}) against an inbound webhook
 * payload, so which workflow a webhook starts is entirely YAML-driven —
 * no per-workflow Java code needed.
 */
@Component
public class TriggerConditionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    /** A trigger with no condition always matches. */
    public boolean matches(TriggerDefinition trigger, Map<String, Object> payload) {
        if (trigger.condition() == null || trigger.condition().isBlank()) {
            return true;
        }
        StandardEvaluationContext context = new StandardEvaluationContext(payload);
        context.addPropertyAccessor(new MapAccessor());
        Expression expression = parser.parseExpression(trigger.condition());
        Boolean result = expression.getValue(context, Boolean.class);
        return Boolean.TRUE.equals(result);
    }
}
