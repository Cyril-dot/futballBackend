package com.speedbet.api.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class MistralClient {

    // ── Config lives in application.properties ────────────────────────────
    //
    //   ai.huggingface.base-url=https://router.huggingface.co/v1
    //   ai.huggingface.api-key=${HF_TOKEN}          ← set HF_TOKEN as env var
    //   ai.huggingface.model=Qwen/Qwen3-Coder-Next:novita
    //
    // For local dev put the real value in application-local.properties
    // and add that file to .gitignore. Never commit the raw token.
    // ─────────────────────────────────────────────────────────────────────

    private static final int TOKENS_PER_CRASH_POINT = 28;
    private static final int TOKEN_ENVELOPE_OVERHEAD = 150;
    private static final int MAX_TOKENS_HARD_CAP     = 4096;

    private final WebClient    client;
    private final String       model;
    private final ObjectMapper mapper = new ObjectMapper();

    public MistralClient(
            WebClient.Builder builder,
            @Value("${ai.huggingface.base-url}") String baseUrl,
            @Value("${ai.huggingface.api-key}")  String apiKey,
            @Value("${ai.huggingface.model}")    String model
    ) {
        this.model  = model;
        this.client = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ── Public methods ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> predictMatch(Map<String, Object> matchContext) {
        try {
            var response = callModel(
                    "You are a professional football analyst. Output STRICT JSON only. No markdown, no explanation.",
                    buildMatchPrompt(matchContext),
                    1000
            );
            return mapper.readValue(cleanJson(response), Map.class);
        } catch (Exception e) {
            log.warn("Match prediction failed: {}", e.getMessage());
            return getDemoPrediction(matchContext);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Double> generateCrashPoints(String gameName, int count, Map<String, Object> distribution) {
        try {
            int maxTokens = Math.min(
                    TOKEN_ENVELOPE_OVERHEAD + (count * TOKENS_PER_CRASH_POINT),
                    MAX_TOKENS_HARD_CAP
            );

            var response = callModel(
                    "You are a crash game RNG engine. Output STRICT JSON only. No explanation. " +
                            "Your entire response must be a single valid JSON object: " +
                            "{\"crash_points\": [<array of " + count + " numbers>]}. " +
                            "Do NOT truncate. All " + count + " values are required.",
                    buildCrashPrompt(gameName, count, distribution),
                    maxTokens
            );

            var cleaned = cleanJson(response);
            var parsed  = mapper.readValue(cleaned, Map.class);
            var points  = (List<Double>) parsed.get("crash_points");

            if (points == null || points.isEmpty()) {
                log.warn("AI returned empty crash_points for {}, using PRNG fallback", gameName);
                return generateFallbackCrashPoints(count, distribution);
            }
            if (points.size() < count) {
                log.warn("AI returned only {}/{} crash points for {}, padding with PRNG",
                        points.size(), count, gameName);
                var extra = generateFallbackCrashPoints(count - points.size(), distribution);
                points = new java.util.ArrayList<>(points);
                points.addAll(extra);
            }
            return points;

        } catch (Exception e) {
            log.warn("Crash generation failed, using PRNG fallback: {}", e.getMessage());
            return generateFallbackCrashPoints(count, distribution);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generateCrashInsight(String game, List<Double> recentCrashes) {
        try {
            var prompt = mapper.writeValueAsString(Map.of(
                    "game",           game,
                    "recent_crashes", recentCrashes,
                    "task",           "Generate a 2-sentence insight for bettors. Be direct. " +
                            "Mention streak patterns and suggest a cashout zone. " +
                            "Output JSON: {\"insight\": string, \"suggested_cashout_min\": float, \"suggested_cashout_max\": float}"
            ));
            var response = callModel(
                    "You are a crash game analyst. Output STRICT JSON only.", prompt, 256);
            return mapper.readValue(cleanJson(response), Map.class);
        } catch (Exception e) {
            log.warn("Crash insight failed: {}", e.getMessage());
            return getDemoCrashInsight();
        }
    }

    // ── Core API caller ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callModel(String systemPrompt, String userContent, int maxTokens) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model",    model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userContent)
        ));
        body.put("temperature", 0.2);
        body.put("top_p",       0.7);
        body.put("max_tokens",  maxTokens);

        var response = (Map<String, Object>) client.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(60))
                .onErrorResume(e -> {
                    log.error("HuggingFace Router API error: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();

        if (response == null)
            throw new RuntimeException("No response from HuggingFace Router");

        var choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty())
            throw new RuntimeException("Empty choices from HuggingFace Router");

        var choice       = choices.get(0);
        var finishReason = (String) choice.getOrDefault("finish_reason", "unknown");
        if ("length".equals(finishReason)) {
            throw new RuntimeException(
                    "HuggingFace Router response truncated (finish_reason=length). " +
                            "Increase max_tokens or reduce batch size.");
        }

        var message = (Map<String, Object>) choice.get("message");
        var content = (String) message.get("content");
        if (content == null || content.isBlank())
            throw new RuntimeException("Empty content from HuggingFace Router");

        log.debug("HuggingFace Router response received ({} chars, finish_reason={})",
                content.length(), finishReason);
        return content;
    }

    // ── Prompt builders ───────────────────────────────────────────────────

    private String buildMatchPrompt(Map<String, Object> ctx) {
        try   { return mapper.writeValueAsString(ctx); }
        catch (Exception e) { return ctx.toString(); }
    }

    private String buildCrashPrompt(String game, int count, Map<String, Object> distribution) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "game",         game,
                    "count",        count,
                    "distribution", distribution,
                    "constraints",  Map.of(
                            "no_consecutive_extreme", true,
                            "min_gap_between_high",   8
                    )
            ));
        } catch (Exception e) { return "{}"; }
    }

    /**
     * Robust JSON cleaner.
     * Finds the first '{' and last '}' to extract the JSON object,
     * stripping any markdown fences or conversational filler.
     */
    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        var cleaned = raw.strip();

        // Remove markdown code blocks if present
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```json\\s*", "").replaceFirst("```\\s*", "");
            var end = cleaned.lastIndexOf("```");
            if (end >= 0) cleaned = cleaned.substring(0, end);
        }

        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned.strip();
    }

    // ── Fallback data ─────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private Map<String, Object> getDemoPrediction(Map<String, Object> ctx) {
        return Map.of(
                "win_probability",     Map.of("home", 0.48, "draw", 0.27, "away", 0.25),
                "predicted_score",     Map.of("home", 2, "away", 1),
                "both_teams_to_score", true,
                "over_under_25",       "OVER",
                "correct_scores",      List.of(
                        Map.of("score", "2-1", "prob", 0.18),
                        Map.of("score", "1-0", "prob", 0.12),
                        Map.of("score", "2-0", "prob", 0.10)
                ),
                "confidence",     0.62,
                "reasoning",      "Based on recent form and head-to-head record, the home team has a " +
                        "clear advantage. Their pressing intensity and home crowd support " +
                        "typically yields 2+ goals.",
                "suggested_odds", Map.of("home", 2.1, "draw", 3.4, "away", 3.2)
        );
    }

    public List<Double> generateFallbackCrashPoints(int count, Map<String, Object> distribution) {
        var rand   = new Random(System.currentTimeMillis());
        var points = new java.util.ArrayList<Double>(count);

        double lowPct  = ((Number) distribution.getOrDefault("low_pct",  0.40)).doubleValue();
        double medPct  = ((Number) distribution.getOrDefault("med_pct",  0.35)).doubleValue();
        double highPct = ((Number) distribution.getOrDefault("high_pct", 0.20)).doubleValue();
        int lastHighIndex = -10;

        for (int i = 0; i < count; i++) {
            double r = rand.nextDouble();
            double val;
            if (r < lowPct)
                val = 1.00 + rand.nextDouble();
            else if (r < lowPct + medPct)
                val = 2.01 + rand.nextDouble() * 2.99;
            else if (r < lowPct + medPct + highPct && i - lastHighIndex >= 8) {
                val = 5.01 + rand.nextDouble() * 14.99;
                lastHighIndex = i;
            } else
                val = 1.00 + rand.nextDouble() * 3.0;
            points.add(Math.round(val * 100.0) / 100.0);
        }
        return points;
    }

    private Map<String, Object> getDemoCrashInsight() {
        return Map.of(
                "insight",               "Recent rounds show a LOW streak of 4 consecutive rounds. " +
                        "Statistically, a MEDIUM or HIGH round is due. Consider cashing out between 2x–4x.",
                "suggested_cashout_min", 2.0,
                "suggested_cashout_max", 4.0
        );
    }
}