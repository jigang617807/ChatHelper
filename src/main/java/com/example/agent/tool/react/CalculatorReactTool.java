package com.example.agent.tool.react;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CalculatorReactTool implements ReactTool {

    private static final Pattern SIMPLE_EXPRESSION = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Calculate a simple binary arithmetic expression. Supported operators: +, -, *, /.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "expression": "string, required, for example 12.5 * 4"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String expression = ToolArguments.string(arguments, "expression");
        if (expression == null || expression.isBlank()) {
            return ToolExecutionResult.failure("expression is required.");
        }
        Matcher matcher = SIMPLE_EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            return ToolExecutionResult.failure("Only simple binary expressions are supported, for example: 12.5 * 4.");
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
        if (result == null) {
            return ToolExecutionResult.failure("Division by zero is not allowed.");
        }
        return ToolExecutionResult.success(expression + " = " + result.toPlainString());
    }
}
