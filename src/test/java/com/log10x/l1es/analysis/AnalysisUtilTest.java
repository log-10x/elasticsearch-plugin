package com.log10x.l1es.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

import com.log10x.l1es.query.predicate.PhrasePredicate;

/**
 * Template text next to a value, and a hash next to its first value, as the
 * standard analyzer (Elasticsearch's default for text fields) reads them.
 */
class AnalysisUtilTest {

	private static final AnalyzerTokenizer STANDARD = AnalyzerTokenizer.of(new StandardAnalyzer(), "message");
	private static final char OPEN = AnalysisUtil.OPEN_EDGE;

	@Test
	void aWordRunsOnIntoTheValueAfterIt() {
		// query C:\inetpub\wwwroot\index.aspx?id=16317 is written "query C:\inetpub\wwwroot\index.$=$"
		List<String> tokens = AnalysisUtil.tokenizePatternWithEdges("query C:\\inetpub\\wwwroot\\index.$=$", STANDARD);
		assertEquals(List.of("query", "c", "inetpub", "wwwroot", "index" + OPEN, "$", "$"), tokens);
		// the decoded text reads index.aspx as one token
		assertEquals(List.of("query", "c", "inetpub", "wwwroot", "index.aspx", "id", "16317"),
				STANDARD.tokenList("query C:\\inetpub\\wwwroot\\index.aspx?id=16317"));
		PhrasePredicate phrase = new PhrasePredicate(STANDARD, STANDARD.tokenList("C:\\inetpub\\wwwroot\\index.aspx"));
		assertTrue(phrase.test(tokens, true, false));
		assertFalse(phrase.test(AnalysisUtil.tokenizePattern("query C:\\inetpub\\wwwroot\\index.$=$", STANDARD), true, false));
	}

	@Test
	void aValueRunsOnIntoTheWordAfterIt() {
		// C:\Windows\System32\lsass.exe started, options off: "C:\Windows\System32\$.exe started"
		List<String> tokens = AnalysisUtil.tokenizePatternWithEdges("C:\\Windows\\System32\\$.exe started", STANDARD);
		assertEquals(List.of("c", "windows", "system32", "$", OPEN + "exe", "started"), tokens);
		PhrasePredicate phrase = new PhrasePredicate(STANDARD, List.of("lsass.exe", "started"));
		assertTrue(phrase.test(tokens, true, false));
		assertFalse(phrase.test(AnalysisUtil.tokenizePattern("C:\\Windows\\System32\\$.exe started", STANDARD), true, false));
	}

	@Test
	void aBreakAtTheEdgeKeepsTheWordWhole() {
		assertEquals(List.of("user", "$", "logged", "on"), AnalysisUtil.tokenizePatternWithEdges("user $ logged on", STANDARD));
		assertEquals(List.of("src", "$", "dst", "$"), AnalysisUtil.tokenizePatternWithEdges("src=$ dst=$", STANDARD));
	}

	@Test
	void openTokensMatchTheStartEndOrPartOfAToken() {
		assertTrue(AnalysisUtil.tokenMatches("index" + OPEN, "index.aspx"));
		assertTrue(AnalysisUtil.tokenMatches("index" + OPEN, "index"));
		assertFalse(AnalysisUtil.tokenMatches("index" + OPEN, "myindex"));
		assertTrue(AnalysisUtil.tokenMatches(OPEN + "exe", "lsass.exe"));
		assertFalse(AnalysisUtil.tokenMatches(OPEN + "exe", "exec"));
		assertTrue(AnalysisUtil.tokenMatches(OPEN + "b" + OPEN, "abc"));
		assertTrue(AnalysisUtil.tokenMatches("exe", "exe"));
		assertFalse(AnalysisUtil.tokenMatches("exe", "lsass.exe"));
	}

	@Test
	void aHashEndingInADigitRunsOnIntoAFirstValueThatStartsWithOne() {
		String hash = "Fk4ysOHjB8";
		List<String> hashTokens = STANDARD.tokenList(hash);
		String prefix = AnalysisUtil.hashRunOnPrefix(hash, hashTokens, ',');
		assertEquals("fk4ysohjb8,", prefix);
		List<String> compact = STANDARD.tokenList("~Fk4ysOHjB8,1793571468607,10.0.3.177,13590");
		assertFalse(compact.contains("fk4ysohjb8"));
		assertTrue(compact.get(0).startsWith(prefix));
	}

	@Test
	void otherHashesNeedNoPrefix() {
		assertNull(AnalysisUtil.hashRunOnPrefix("-?NpceT4hrl", STANDARD.tokenList("-?NpceT4hrl"), ','));
		assertNull(AnalysisUtil.hashRunOnPrefix("-&R&  97}cb", STANDARD.tokenList("-&R&  97}cb"), ','));
		assertTrue(STANDARD.tokenList("~-?NpceT4hrl,1793571468607").contains("npcet4hrl"));
	}
}
