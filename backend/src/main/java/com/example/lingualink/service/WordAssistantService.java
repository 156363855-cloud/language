package com.example.lingualink.service;

import com.example.lingualink.dto.ExplainWordRequest;
import com.example.lingualink.model.WordExplanation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class WordAssistantService {

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WordExplanation explain(ExplainWordRequest request) {
        if (request.word() == null || request.word().isBlank()) {
            throw new IllegalArgumentException("词语不能为空");
        }

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return buildFallback(request);
        }

        try {
            return requestDeepSeek(request, apiKey);
        } catch (Exception exception) {
            return buildFallback(request);
        }
    }

    private WordExplanation requestDeepSeek(ExplainWordRequest request, String apiKey) throws IOException, InterruptedException {
        String interfaceLanguage = normalizeInterfaceLanguage(request.interfaceLanguage());
        String explanationLanguageName = switch (interfaceLanguage) {
            case "ja" -> "日语";
            case "en" -> "英语";
            default -> "简体中文";
        };
        String prompt = """
                你是一个简洁的语言词汇老师。请根据给定词语、语言和句子，返回一个 JSON 对象，不要输出 JSON 以外的任何内容。
                字段要求：
                reading: 读音或音标。日语填假名，英语可填音标或留空，中文可留空
                meaning: 用%s解释，1-2 句，简洁
                usage: 用%s说明这个词常见用法或语感，1 句
                example: 用这个词造一个符合原语言的简短例句，并补一行%s解释，放在同一个字符串里

                词语：%s
                语言：%s
                原句：%s
                """.formatted(
                explanationLanguageName,
                explanationLanguageName,
                explanationLanguageName,
                request.word().trim(),
                request.language() == null || request.language().isBlank() ? "ja" : request.language().trim(),
                request.sentence() == null ? "" : request.sentence().trim()
        );

        Map<String, Object> payload = Map.of(
                "model", "deepseek-chat",
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个输出稳定 JSON 的多语言词汇解释助手。"),
                        Map.of("role", "user", "content", prompt)
                )
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(DEEPSEEK_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("词语解释接口调用失败");
        }

        DeepSeekResponse deepSeekResponse = objectMapper.readValue(response.body(), DeepSeekResponse.class);
        if (deepSeekResponse.choices() == null || deepSeekResponse.choices().isEmpty()) {
            throw new IllegalStateException("模型没有返回解释内容");
        }
        String content = deepSeekResponse.choices().get(0).message().content();
        JsonNode jsonNode = objectMapper.readTree(extractJsonObject(content));
        return new WordExplanation(
                request.word().trim(),
                jsonNode.path("reading").asText(""),
                jsonNode.path("meaning").asText(""),
                jsonNode.path("usage").asText(""),
                jsonNode.path("example").asText(""),
                "ai"
        );
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型返回格式不正确");
        }
        return content.substring(start, end + 1);
    }

    private WordExplanation buildFallback(ExplainWordRequest request) {
        String word = request.word().trim();
        String sentence = request.sentence() == null ? "" : request.sentence().trim();
        String interfaceLanguage = normalizeInterfaceLanguage(request.interfaceLanguage());
        String usage = switch (interfaceLanguage) {
            case "ja" -> sentence.isBlank()
                    ? "この語は文脈と一緒に覚えるのが自然です。"
                    : "この語は今の文脈に合わせて意味やニュアンスを判断すると覚えやすいです。";
            case "en" -> sentence.isBlank()
                    ? "This word is easier to remember with a full sentence."
                    : "This word is best understood with the surrounding context.";
            default -> sentence.isBlank()
                    ? "这个词通常需要放进完整语境里理解，建议结合整句一起记。"
                    : "这个词出现在当前这句里，通常要结合前后文判断语气和对象。";
        };
        String meaning = switch (interfaceLanguage) {
            case "ja" -> "これは現在の文脈に基づいた簡易説明です。あとでより詳しい説明を補えます。";
            case "en" -> "This is a lightweight explanation based on the current context. You can enrich it later.";
            default -> "这是一个基于当前上下文生成的简短释义入口。你可以先加入生词本，后面再补充更完整解释。";
        };
        String example = sentence.isBlank() ? word : sentence;

        return new WordExplanation(
                word,
                "",
                meaning,
                usage,
                example,
                "fallback"
        );
    }

    private String normalizeInterfaceLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "zh";
        }
        String normalized = language.trim().toLowerCase();
        return switch (normalized) {
            case "ja", "en" -> normalized;
            default -> "zh";
        };
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepSeekResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {
    }
}
