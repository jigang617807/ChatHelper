package com.example.agent.tool.react;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class DateTimeReactTool implements ReactTool {

    @Override
    public String name() {
        return "date_time";
    }

    @Override
    public String description() {
        return "Get the current date and time for the server timezone.";
    }

    @Override
    public String parameters() {
        return "{}";
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String now = LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return ToolExecutionResult.success("Current server time: " + now);
    }
}
