package com.log10x.l1es.dml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.log10x.config.EncoderConfig;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The shared reader vectors (FORMAT_SPEC S6.4), decoded the way the plugin
 * decodes a compact field: templates and encoded lines the engine wrote for
 * events read with CR LF, inside and at the end of an event, a CR alone inside
 * an event, and the same lines read with LF, with the exact text each decodes
 * to. A decoded event read with CR LF ends in its CR. The same file is checked
 * by the engine's decoder, the Java and JavaScript decoders and the format's
 * reference implementation (log10x-format fixtures/line-end-vectors.json).
 */
class LineEndVectorsTest {

	private static String visible(String text) {
		return (text == null) ? "null" : text.replace("\r", "\\r").replace("\n", "\\n");
	}

	@Test
	void everyEncodedLineDecodesToItsText() throws IOException {

		JsonNode data;

		try (InputStream in = LineEndVectorsTest.class.getResourceAsStream("/line-end-vectors.json")) {
			data = new ObjectMapper().readTree(in);
		}

		DmlUtil.configUpdated(EncoderConfig.get(Settings.builder()
			.put("encoder.hasEncodedLinePrefix", true)
			.put("encoder.encodedLinePrefix", "~")
			.put("encoder.valueSeperator", ",")
			.build()));

		JsonNode templates = data.get("templates");

		int events = 0;

		for (JsonNode event : data.get("events")) {

			String encoded = event.get("encoded").asText();

			int comma = encoded.indexOf(',');

			String key = (comma == -1) ? encoded.substring(1) : encoded.substring(1, comma);
			String values = (comma == -1) ? "" : encoded.substring(comma + 1);

			String decoded = DmlUtil.decode(key, values, templates.get(key).asText());

			assertEquals(visible(event.get("text").asText()), visible(decoded), encoded);

			events++;
		}

		assertTrue(events > 0);
	}
}
