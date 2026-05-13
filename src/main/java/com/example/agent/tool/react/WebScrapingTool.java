package com.example.agent.tool.react;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebScrapingTool implements ReactTool {

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final WebClient webClient;

    public WebScrapingTool(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "web_scraping";
    }

    @Override
    public String description() {
        return "Fetch a public webpage and extract readable text. Use this after web_search when a specific result needs evidence.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "url": "string, required, public http/https URL",
                  "maxChars": "number, optional, 1000-12000"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String url = ToolArguments.string(arguments, "url");
        int maxChars = ToolArguments.integer(arguments, "maxChars", 8000, 1000, 12000);
        try {
            URI uri = UrlSafety.requirePublicHttpUrl(url);
            String html = webClient.get()
                    .uri(uri)
                    .header("User-Agent", "RagAgent/1.0")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (html == null || html.isBlank()) {
                return ToolExecutionResult.failure("The webpage returned no content.");
            }
            String title = extractTitle(html);
            String text = htmlToText(html);
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "\n\n[TRUNCATED]";
            }
            return ToolExecutionResult.success("Title: " + title + "\nURL: " + uri + "\n\n" + text);
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Web scraping failed: " + ex.getMessage());
        }
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE.matcher(html);
        if (matcher.find()) {
            return cleanText(matcher.group(1));
        }
        return "(untitled)";
    }

    private String htmlToText(String html) {
        String noScripts = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        String text = noScripts.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n")
                .replaceAll("(?is)</h[1-6]>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        return cleanText(text);
    }

    private String cleanText(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .trim();
    }
}
