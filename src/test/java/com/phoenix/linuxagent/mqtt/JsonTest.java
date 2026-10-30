// SPDX-License-Identifier: Apache-2.0

package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesTheMessagesCoreSends() {
        Map<String, Object> task = Json.parseObject(
                "{ \"taskId\" : \"task-001\", \"type\":\"Monte-Carlo-Pi\", \"samples\":2000000,"
                        + "\"attemptId\":\"7b1adffa-a4c8\",\"attemptNumber\":2, \"x\":null, \"ok\":true,"
                        + "\"ratio\":-1.5e2, \"list\":[1,\"two\",{\"three\":3}], \"empty\":{} }");

        assertEquals("task-001", Json.string(task, "taskId"));
        assertEquals(2_000_000L, Json.number(task, "samples", 0));
        assertEquals(2L, Json.number(task, "attemptNumber", 0));
        assertTrue(task.containsKey("x"));
        assertNull(task.get("x"));
        assertEquals(Boolean.TRUE, task.get("ok"));
        assertEquals(-150.0, task.get("ratio"));
        assertEquals(Arrays.asList(1L, "two", Map.of("three", 3L)), task.get("list"));
        assertEquals(Map.of(), task.get("empty"));
    }

    @Test
    void decodesEscapes() {
        Map<String, Object> body = Json.parseObject("{\"s\":\"q\\\" b\\\\ s\\/ n\\n t\\t u\\u00e9\\u20ac\"}");
        assertEquals("q\" b\\ s/ n\n t\t u\u00e9\u20ac", body.get("s"));
    }

    @Test
    void accessorsNeverThrowOnMissingOrMistypedFields() {
        Map<String, Object> body = Json.parseObject("{\"n\":\"12\",\"d\":2.5,\"w\":3.0,\"s\":7}");

        assertNull(Json.string(body, "missing"));
        assertNull(Json.string(body, "s"));
        assertEquals("fallback", Json.string(body, "missing", "fallback"));
        assertEquals(-1, Json.number(body, "n", -1));
        assertEquals(-1, Json.number(body, "d", -1));
        assertEquals(3, Json.number(body, "w", -1));
        assertEquals(-1, Json.number(body, "missing", -1));
    }

    @Test
    void refusesWhatIsNotAJsonObject() {
        for (String text : List.of("", "[]", "\"s\"", "{", "{\"a\":}", "{\"a\":1,}", "{\"a\":1} x",
                "{a:1}", "{\"a\":\"unterminated}", "{\"a\":tru}", "{\"a\":01x}", "{\"a\":\"\\x\"}",
                "{\"a\":\"\u0001\"}")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parseObject(text), text);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(null));
    }

    @Test
    void refusesDeepNesting() {
        String deep = "{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}";
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(deep));
    }

    @Test
    void writesWhatItReads() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("s", "quote\" backslash\\ newline\n tab\t ctl\u0001 \u2028 \u00e9");
        body.put("l", 42L);
        body.put("i", 7);
        body.put("d", 12.5);
        body.put("neg", -1.0);
        body.put("b", false);
        body.put("n", null);
        body.put("list", List.of("attempt-id"));

        String text = Json.write(body);
        assertEquals(body.get("s"), Json.parseObject(text).get("s"));
        assertTrue(text.contains("\"d\":12.5"));
        assertTrue(text.contains("\"neg\":-1.0"));
        assertTrue(text.contains("\"i\":7"));
        assertTrue(text.contains("\"list\":[\"attempt-id\"]"));
        assertTrue(text.contains("\"n\":null"));
        assertTrue(text.contains("\\u0001"));
        assertTrue(text.contains("\\u2028"));
    }

    @Test
    void theBuilderKeepsFieldOrder() {
        assertEquals("{\"b\":1,\"a\":\"x\"}", Json.object().put("b", 1).put("a", "x").toString());
    }

    @Test
    void refusesValuesJsonCannotHold() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("x", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("x", new Object())));
    }
}
