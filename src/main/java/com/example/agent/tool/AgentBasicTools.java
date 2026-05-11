package com.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentBasicTools {

    private static final Pattern SIMPLE_EXPRESSION = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    @Tool(name = "date_time", description = "Get the current date and time for the server timezone.")
    public String dateTime() {
        return LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(name = "calculator", description = "Calculate a simple binary arithmetic expression. Supported operators: +, -, *, /.")
    public String calculator(@ToolParam(description = "A simple expression such as 12.5 * 4.") String expression) {
        if (expression == null || expression.isBlank()) {
            return "Expression is required.";
        }
        Matcher matcher = SIMPLE_EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            return "Only simple binary expressions are supported, for example: 12.5 * 4.";
        }

        BigDecimal left = new BigDecimal(matcher.group(1));
        BigDecimal right = new BigDecimal(matcher.group(3));
        String operator = matcher.group(2);

        BigDecimal result = switch (operator) {
            case "+" -> left.add(right);
            case "-" -> left.subtract(right);
            case "*" -> left.multiply(right);
            case "/" -> {
                if (BigDecimal.ZERO.compareTo(right) == 0) {
                    yield null;
                }
                yield left.divide(right, 8, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default -> throw new IllegalStateException("Unexpected operator: " + operator);
        };

        return result == null ? "Division by zero is not allowed." : expression + " = " + result.toPlainString();
    }

    @Tool(name = "project_resume_writer", description = "Rewrite a project feature description into concise resume bullet points.")
    public String projectResumeWriter(
            @ToolParam(description = "Project feature or technical implementation details.") String projectDetails) {
        if (projectDetails == null || projectDetails.isBlank()) {
            return "Project details are required.";
        }
        return """
                Resume bullet template:
                - Built an AI knowledge-base agent module based on Spring AI Tool Calling and MCP, integrating local tools and RAG retrieval as callable capabilities.
                - Designed persistent agent execution traces including plan, tool call, tool result and final answer records, enabling replay and troubleshooting.
                - Implemented SSE streaming responses and session history persistence to improve the interactive user experience.

                User provided details:
                %s
                """.formatted(projectDetails);
    }
}
