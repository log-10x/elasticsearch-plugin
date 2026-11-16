package com.log10x.l1es.query.predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The template side of a phrase search: tokens of a template's static text, with "$" for
 * each value slot, as AnalysisUtil.tokenizePattern returns them. One value can hold
 * several analyzed tokens, for example a Windows path the encoder writes as one value.
 */
class PhrasePredicateTest {

	private static PhrasePredicate phrase(String... targets) {
		return new PhrasePredicate(null, List.of(targets));
	}

	@Test
	void aValueHoldsTheRestOfThePhrase() {
		// <Data Name='ObjectName'>C:\Program $</Data>, value "Files\Google\Chrome\chrome.exe"
		List<String> template = List.of("data", "name", "objectname", "c", "program", "$", "data");
		assertTrue(phrase("program", "files", "google").test(template, true, false));
		assertTrue(phrase("c", "program", "files").test(template, true, false));
	}

	@Test
	void aValueHoldsTheStartOfThePhrase() {
		// $ started, value "C:\Windows\System32\lsass.exe"
		List<String> template = List.of("$", "started");
		assertTrue(phrase("system32", "lsass.exe", "started").test(template, true, false));
	}

	@Test
	void aValueBetweenTwoStaticTokens() {
		List<String> template = List.of("copy", "$", "to", "$");
		assertTrue(phrase("copy", "share", "file.bin", "to").test(template, true, false));
		assertTrue(phrase("copy", "to").test(template, true, false));
	}

	@Test
	void oneTokenPerValueStillMatches() {
		List<String> template = List.of("c", "windows", "system32", "$", "exe", "started");
		assertTrue(phrase("c", "windows", "system32", "lsass.exe").test(template, true, false));
	}

	@Test
	void aPhraseWhollyInsideAValueNeedsTheFullWildcardFlag() {
		// launch $, value "C:\Program Files\App\app.exe"
		List<String> template = List.of("launch", "$");
		assertFalse(phrase("program", "files").test(template, true, false));
		assertTrue(phrase("program", "files").test(template, true, true));
	}

	@Test
	void staticTokensMustStillMatchInOrder() {
		List<String> template = List.of("copy", "$", "to", "disk");
		assertFalse(phrase("to", "copy").test(template, true, false));
		assertFalse(phrase("disk", "copy").test(template, true, false));
		assertFalse(phrase("move", "x").test(template, true, false));
		assertFalse(phrase("copy", "x").test(List.of("copy", "y"), true, false));
	}

	@Test
	void strictTestIsUnchanged() {
		List<String> template = List.of("c", "program", "$", "data");
		assertTrue(phrase("c", "program").test(template, false, false));
		assertFalse(phrase("program", "files").test(template, false, false));
	}
}
