package com.log10x.l1es.analysis;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import com.log10x.l1es.util.L1esConsts;

/**
 * Utility class for common analysis related actions.
 */
public class AnalysisUtil {

	/**
	 * Use an {@link AnalyzerTokenizer} to tokenize a given pattern, while taking
	 * into account the place holders for variables (defined by
	 * {@link L1esConsts#VARIABLE_TOKEN})
	 * 
	 * Each occurrence of {@link L1esConsts#VARIABLE_TOKEN} remains unaffected by
	 * tokenization.
	 * 
	 * @param pattern   Pattern to tokenize
	 * @param tokenizer {@link AnalyzerTokenizer} to tokenize with
	 * 
	 * @return list of resulting tokens, with {@link L1esConsts#VARIABLE_TOKEN}
	 *         placed as needed.
	 */
	public static List<String> tokenizePattern(String pattern, AnalyzerTokenizer tokenizer) {
		// We need to pass -1 to split, to avoid dropping empty parts.
		//
		// We need the empty parts for our logic embedding L1esConsts.VARIABLE_TOKEN to
		// succeed.
		//
		// We Pattern.quote the L1esConsts.VARIABLE_TOKEN because it might be a special
		// character for regex as well.
		//
		String[] parts = pattern.split(Pattern.quote(L1esConsts.VARIABLE_TOKEN), -1);

		List<String> result = new LinkedList<>();

		for (int i = 0; i < parts.length; i++) {

			if (i > 0) {
				result.add(L1esConsts.VARIABLE_TOKEN);
			}

			String part = parts[i];

			if (part.isEmpty()) {

				// No point processing empty strings.
				// Empty strings can happen if we have L1esConsts.VARIABLE_TOKEN at the
				// beginning or end of 'pattern'.
				//
				continue;
			}

			tokenizer.tokenize(part, result);
		}

		return result;
	}

	/**
	 * Marks a static token whose word the neighbouring value can continue: the
	 * decoded text has no break between them, so the analyzer may read the
	 * static text and the value as one token ({@code index.} + {@code aspx?id}
	 * reads {@code index.aspx}). Never part of an analyzed token.
	 */
	public static final char OPEN_EDGE = '\u0000';

	/**
	 * {@link #tokenizePattern}, with each static token that touches a value slot
	 * without a break marked by {@link #OPEN_EDGE} on that side: at the start
	 * when a value ends right before it, at the end when a value starts right
	 * after it. For the wild card form of a phrase test only.
	 *
	 * <p>A side is open when the static text there ends (or starts) with a
	 * letter or digit, or with one of {@code . : ' _ , ;} next to a letter or
	 * digit: characters a word-break analyzer can read inside one token. A side
	 * marked open where the analyzer does break only widens the test; the
	 * decoded text is checked afterwards.
	 */
	public static List<String> tokenizePatternWithEdges(String pattern, AnalyzerTokenizer tokenizer) {
		String[] parts = pattern.split(Pattern.quote(L1esConsts.VARIABLE_TOKEN), -1);

		List<String> result = new LinkedList<>();

		for (int i = 0; i < parts.length; i++) {

			if (i > 0) {
				result.add(L1esConsts.VARIABLE_TOKEN);
			}

			String part = parts[i];

			if (part.isEmpty()) {
				continue;
			}

			List<String> tokens = tokenizer.tokenList(part);

			if (tokens.isEmpty()) {
				continue;
			}

			boolean openStart = (i > 0) && (opensWord(part, 0, 1));
			boolean openEnd = (i < parts.length - 1) && (opensWord(part, part.length() - 1, -1));

			int last = tokens.size() - 1;

			for (int t = 0; t <= last; t++) {
				String token = tokens.get(t);

				if ((t == 0) && (openStart)) {
					token = OPEN_EDGE + token;
				}

				if ((t == last) && (openEnd)) {
					token = token + OPEN_EDGE;
				}

				result.add(token);
			}
		}

		return result;
	}

	/**
	 * True when the character at {@code at} (the edge of a static part) lets a
	 * word run on across the edge: a letter or digit, or a word-joining
	 * punctuation character followed (in direction {@code step}) by a letter or
	 * digit.
	 */
	private static boolean opensWord(String part, int at, int step) {
		char c = part.charAt(at);

		if (Character.isLetterOrDigit(c)) {
			return true;
		}

		switch (c) {
			case '.':
			case ':':
			case '\'':
			case '_':
			case ',':
			case ';':
				int next = at + step;
				return (next >= 0) && (next < part.length()) && (Character.isLetterOrDigit(part.charAt(next)));
			default:
				return false;
		}
	}

	/**
	 * The start of the term a compact event holds in place of its hash's own
	 * term, or null when it holds the hash's term. A hash that is one analyzed
	 * token and ends in a digit runs on into a first value that starts with a
	 * digit: a word-break analyzer reads {@code 8,1793571468607} as one number,
	 * so {@code ~Fk4ysOHjB8,1793571468607,...} holds the term
	 * {@code fk4ysohjb8,1793571468607,...}, not {@code fk4ysohjb8}. Such an event
	 * is found by the prefix {@code fk4ysohjb8,}. A hash of several tokens is
	 * still found by the others.
	 *
	 * @param hash      the template hash
	 * @param tokens    the hash analyzed by the field's analyzer
	 * @param separator the value separator of compact events
	 */
	public static String hashRunOnPrefix(String hash, List<String> tokens, char separator) {
		if ((tokens.size() != 1) || (hash.isEmpty())) {
			return null;
		}

		char last = hash.charAt(hash.length() - 1);

		if ((last < '0') || (last > '9')) {
			return null;
		}

		return tokens.get(0) + separator;
	}

	/**
	 * Whether a token of {@link #tokenizePatternWithEdges} matches an analyzed
	 * token of the text: equal, or for a side marked open, the static word may
	 * be only the start (open end), the end (open start) or a part (both) of the
	 * text's token.
	 */
	public static boolean tokenMatches(String patternToken, String token) {
		int length = patternToken.length();

		if ((length == 0) ||
			((patternToken.charAt(0) != OPEN_EDGE) && (patternToken.charAt(length - 1) != OPEN_EDGE))) {

			return patternToken.equals(token);
		}

		boolean openStart = patternToken.charAt(0) == OPEN_EDGE;
		boolean openEnd = (length > 1) && (patternToken.charAt(length - 1) == OPEN_EDGE);

		String core = patternToken.substring(openStart ? 1 : 0, openEnd ? length - 1 : length);

		if ((openStart) && (openEnd)) {
			return token.contains(core);
		}

		return (openStart) ? token.endsWith(core) : token.startsWith(core);
	}
}
