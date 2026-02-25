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
}
