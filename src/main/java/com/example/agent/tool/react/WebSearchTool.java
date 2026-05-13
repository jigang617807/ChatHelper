package com.example.agent.tool.react;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebSearchTool implements ReactTool {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpoint;

    public WebSearchTool(WebClient webClient,
                         ObjectMapper objectMapper,
                         @Value("${agent.react.web-search.api-key:}") String apiKey,
                         @Value("${agent.react.web-search.endpoint:https://api.tavily.com/search}") String endpoint) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the public web and return concise search results with titles, URLs and snippets. Requires agent.react.web-search.api-key.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "query": "string, required",
                  "topK": "number, optional, 1-8"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        if (apiKey == null || apiKey.isBlank()) {
            return ToolExecutionResult.failure("Web search is not configured. Set agent.react.web-search.api-key or AGENT_REACT_WEB_SEARCH_API_KEY.");
        }
        String query = ToolArguments.string(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure("query is required.");
        }
        int topK = ToolArguments.integer(arguments, "topK", 5, 1, 8);

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("api_key", apiKey);
            payload.put("query", query);
            payload.put("max_results", topK);
            payload.put("search_depth", "basic");
            payload.put("include_answer", false);

            JsonNode response = webClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(20));

            if (response == null) {
                return ToolExecutionResult.failure("Search provider returned an empty response.");
            }
            JsonNode results = response.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return ToolExecutionResult.success("No web search results found for: " + query);
            }

            StringBuilder out = new StringBuilder();
            int index = 1;
            for (JsonNode item : results) {
                out.append(index++).append(". ")
                        .append(text(item, "title")).append("\n")
                        .append("URL: ").append(text(item, "url")).append("\n")
                        .append("Snippet: ").append(firstNonBlank(text(item, "content"), text(item, "snippet"))).append("\n\n");
                if (index > topK) {
                    break;
                }
            }
            return ToolExecutionResult.success(out.toString().trim());
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Web search failed: " + ex.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }
}
