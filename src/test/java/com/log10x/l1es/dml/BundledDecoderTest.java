package com.log10x.l1es.dml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.log10x.decode.SingleEventDecoder;
import com.log10x.decode.lexer.TokenizeContext;

/**
 * The bundled decoder-core, called as {@link DmlUtil#decode(String, String, String)}
 * calls it ({@link SingleEventDecoder} with the plugin's tokenize context, here its
 * default with values separated by a space), expands compact events exactly as the
 * 10x Engine wrote them. The shapes come from engine output: epoch timestamps whose
 * value is the text read, multi-line events whose lines are joined by CR LF or a
 * lone CR, and a source '$' after a '/' (escaped as "///$").
 */
class BundledDecoderTest {

	private static String decode(String values, String template) {
		try {
			return SingleEventDecoder.decode(TokenizeContext.DEFAULT, "h", values, template);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	@Test
	void secondsWithAFractionAreWrittenAsRead() {
		assertEquals("type=SYSCALL msg=audit(1723042099.699:13369): arch=c000003e syscall=59",
			decode("1723042099.699 13369 c000003e 59", "type=SYSCALL msg=audit($(+%s.%f):$): arch=$ syscall=$"));
		assertEquals("ts=1723042099.6 msg=hello", decode("1723042099.6", "ts=$(+%s.%f) msg=hello"));
	}

	@Test
	void millisecondsWithAFractionAreWrittenAsRead() {
		assertEquals("1705313472123.000456 INFO e",
			decode("1705313472123.000456 e", "$(+%s.%N) INFO $"));
	}

	@Test
	void valuesAndTimestampsBeforeACarriageReturn() {
		assertEquals("<Data>%%4432\r\t\t%%4433\r\n\t\t</Data>\n<Mask>0x1</Mask>",
			decode("4432 4433 0x1", "<Data>%%$\r\t\t%%$\r\n\t\t<//Data>\n<Mask>$<//Mask>"));
		assertEquals("at 1723042099.699\r\nnext 4",
			decode("1723042099.699 4", "at $(+%s.%f)\r\nnext $"));
	}

	@Test
	void valueThatIsACarriageReturn() {
		assertEquals("created.\n\r\nCreator Subject: X",
			decode("\r X", "created.\n$\nCreator Subject: $"));
	}

	@Test
	void dollarAfterSlashIsText() {
		assertEquals("$proxy = \"http://$($json.host):$($json.port)\" pid 4592",
			decode("4592", "/$proxy = \"http://///$(/$json.host):/$(/$json.port)\" pid $"));
	}
}
