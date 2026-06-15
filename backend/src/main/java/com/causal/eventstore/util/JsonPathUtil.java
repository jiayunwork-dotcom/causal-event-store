package com.causal.eventstore.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class JsonPathUtil {

    private static final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final int errorPosition;

        public ValidationResult(boolean valid, String errorMessage, int errorPosition) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.errorPosition = errorPosition;
        }

        public boolean isValid() { return valid; }
        public String getErrorMessage() { return errorMessage; }
        public int getErrorPosition() { return errorPosition; }
    }

    public static ValidationResult validateJsonPath(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return new ValidationResult(false, "Expression cannot be empty", 0);
        }
        try {
            JsonPath.compile(expression);
            return new ValidationResult(true, null, -1);
        } catch (Exception e) {
            String msg = e.getMessage();
            int pos = -1;
            if (msg != null) {
                String lower = msg.toLowerCase();
                int idx = lower.indexOf("position");
                if (idx >= 0) {
                    try {
                        String numStr = msg.replaceAll(".*position\\s+(\\d+).*", "$1");
                        pos = Integer.parseInt(numStr);
                    } catch (Exception ignored) {}
                }
            }
            return new ValidationResult(false, msg, pos);
        }
    }

    public static Map<String, Object> extractFields(String jsonPayload, Map<String, String> expressions) {
        Map<String, Object> result = new HashMap<>();
        Object document = jsonPathConfig.jsonProvider().parse(jsonPayload);

        for (Map.Entry<String, String> entry : expressions.entrySet()) {
            String fieldName = entry.getKey();
            String expression = entry.getValue();
            try {
                Object value = JsonPath.using(jsonPathConfig).parse(document).read(expression);
                result.put(fieldName, value);
            } catch (PathNotFoundException e) {
                result.put(fieldName, null);
            } catch (Exception e) {
                log.warn("Failed to evaluate JSON Path {}: {}", expression, e.getMessage());
                result.put(fieldName, null);
            }
        }
        return result;
    }

    public static Object extractSingleField(String jsonPayload, String expression) {
        try {
            Object document = jsonPathConfig.jsonProvider().parse(jsonPayload);
            return JsonPath.using(jsonPathConfig).parse(document).read(expression);
        } catch (PathNotFoundException e) {
            return null;
        } catch (Exception e) {
            log.warn("Failed to evaluate JSON Path {}: {}", expression, e.getMessage());
            return null;
        }
    }

    public static boolean matchesPattern(String value, String pattern) {
        if (pattern == null || pattern.isEmpty() || "*".equals(pattern)) {
            return true;
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return value != null && value.matches(regex);
    }
}
