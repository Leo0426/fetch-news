package app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ModelOutputTest {
    @Test
    void unwrapsOnlyWholeEnvelopesAndDoesNotInventOutput() {
        String json = "{\"body\":\"<think> inside a string\"}";
        assertEquals(json, ModelOutput.normalize(" <think>private</think>\n```JSON\r\n" + json + "\r\n```", true));
        assertEquals("```\ntext\n```", ModelOutput.normalize("```\ntext\n```", false));
        assertEquals("", ModelOutput.normalize("<think>reasoning only</think>", false));
        assertThrows(IllegalStateException.class, () -> ModelOutput.normalize("<think>partial", true));
        assertEquals("Intro\n" + json, ModelOutput.normalize("Intro\n" + json, true));
        String commentary = "```json\n" + json + "\n```\nafter";
        assertEquals(commentary, ModelOutput.normalize(commentary, true));
    }
}
