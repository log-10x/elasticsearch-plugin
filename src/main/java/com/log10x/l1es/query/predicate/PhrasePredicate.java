package com.log10x.l1es.query.predicate;

import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Strings;

import com.log10x.l1es.analysis.AnalysisUtil;
import com.log10x.l1es.analysis.AnalyzerTokenizer;
import com.log10x.l1es.query.match.query.L1esMatchQuery;
import com.log10x.l1es.query.wrap.L1esScorerWrapper;
import com.log10x.l1es.util.L1esConsts;

/**
 * Class for testing whether a {@link String} contains all of the tokens in a
 * given {@code List<String>}, in order.
 * 
 * Used as a cross checker for "match_phrase" queries.
 * 
 * See {@link L1esMatchQuery#l1esPhraseQuery}
 */
public class PhrasePredicate implements Predicate<String> {
	private static final Logger logger = LogManager.getLogger(PhrasePredicate.class);

	private static final int INDEXOFSUBLIST_THRESHOLD = 35;

	private final AnalyzerTokenizer analyzerTokenizer;
	private final List<String> targets;

	/**
	 * @param analyzerTokenizer analyzer responsible for tokenizing test tokens.
	 * @param targets           the target phrase to match, as a {@link List} of
	 *                          tokens.
	 */
	public PhrasePredicate(AnalyzerTokenizer analyzerTokenizer, List<String> targets) {
		this.analyzerTokenizer = analyzerTokenizer;
		this.targets = targets;
	}

	/**
	 * Tests the input {@link String} {@code t} against the internal
	 * {@link #targets}.
	 * 
	 * Uses {@link #analyzerTokenizer} to tokenize {@code t} and then checks if all
	 * of the tokens in {@link #targets} are present and in order.
	 * 
	 * This is what will actually be invoked when doing a cross checking from
	 * {@link L1esScorerWrapper}, and as such doesn't allow the use of wildcards.
	 */
	@Override
	public boolean test(String t) {
		if (Strings.isNullOrEmpty(t)) {
			return targets.isEmpty();
		}

		try {
			List<String> tokens = analyzerTokenizer.tokenList(t);

			return test(tokens, false, false);
		} catch (Exception e) {
			logger.error("Failed checking on \"{}\"", t, e);
			return true;
		}
	}

	/**
	 * Tests the input {@code List<String>} {@code tokens} against the internal
	 * {@link #targets}.
	 * 
	 * Implementation is based on {@link Collections#indexOfSubList}
	 * 
	 * @param tokens                 the {@link List} of tokens to test.
	 * 
	 * @param allowVariableWildcard  When set to {@code true}, will treat each
	 *                               {@link L1esConsts#VARIABLE_TOKEN} in
	 *                               {@code tokens} as a wild card for a run of zero
	 *                               or more tokens of {@link #targets} (see
	 *                               {@link #testWithVariables})
	 * 
	 * @param allowFullWildcardMatch When set to {@code false}, at least one of the
	 *                               tokens in {@code tokens} need to match without
	 *                               being treated as a wild-card
	 * 
	 * @return {@code true} iff {@code tokens} contains all the tokens in
	 *         {@link #targets}, in order.
	 */
	public boolean test(List<String> tokens, boolean allowVariableWildcard, boolean allowFullWildcardMatch) {
		if (targets.isEmpty()) {
			return false;
		}

		if (allowVariableWildcard) {
			return testWithVariables(tokens, allowFullWildcardMatch);
		}

		int targetsSize = targets.size();
		int tokensSize = tokens.size();

		if (targetsSize > tokensSize) {
			return false;
		}

		int maxCandidate = tokensSize - targetsSize;

		// As seen in Collections.indexOfSubList, we have two different implementations.
		//
		// First implementation is for small lists, or ones that are RandomAccess in
		// nature.
		//
		// Second one is for all other lists, where doing a random access to a large
		// list index would be inefficient, and we need to rely on iterators.
		//
		if ((tokensSize < INDEXOFSUBLIST_THRESHOLD)
				|| ((tokens instanceof RandomAccess) && (targets instanceof RandomAccess))) {

			nextCand: for (int candidate = 0; candidate <= maxCandidate; candidate++) {
				boolean hasNonWildcardMatch = false;

				for (int i = 0, j = candidate; i < targetsSize; i++, j++) {
					String currentToken = tokens.get(j);

					if ((allowVariableWildcard) && (currentToken.equals(L1esConsts.VARIABLE_TOKEN))) {

						// Current token is a wildcard, we continue.
						//
						continue;
					}

					String currentTarget = targets.get(i);

					if (currentToken.equals(currentTarget)) {
						hasNonWildcardMatch = true;
						continue;
					}

					// If we reached here, we failed on a match.
					// Will try with next possible candidate.
					//
					continue nextCand;
				}

				// If we reached here, we didn't fail.
				// We still need to check it's not a full-wild-card match though.
				//
				if ((hasNonWildcardMatch) || (allowFullWildcardMatch)) {
					return true;
				}
			}
		} else {
			ListIterator<String> tokensIterator = tokens.listIterator();

			nextCand: for (int candidate = 0; candidate <= maxCandidate; candidate++) {
				boolean hasNonWildcardMatch = false;

				ListIterator<String> targetsIterator = targets.listIterator();

				for (int i = 0; i < targetsSize; i++) {
					String currentToken = tokensIterator.next();
					String currentTarget = targetsIterator.next();

					if ((allowVariableWildcard) && (currentToken.equals(L1esConsts.VARIABLE_TOKEN))) {

						// Current token is a wildcard, we continue.
						//
						continue;
					}

					if (currentToken.equals(currentTarget)) {
						hasNonWildcardMatch = true;
						continue;
					}

					// If we reached here, we failed on a match.
					// Will try with next possible candidate.
					//
					// We need to back up tokens iterator before continuing though.
					//
					for (int j = 0; j < i; j++) {
						tokensIterator.previous();
					}

					continue nextCand;
				}

				// If we reached here, we didn't fail.
				// We still need to check it's not a full-wild-card match though.
				//
				if ((hasNonWildcardMatch) || (allowFullWildcardMatch)) {

					return true;
				}

				// So we did have a full-wild-card match, when one isn't allow.
				//
				// We need to back up tokens iterator before continuing to the next candidate.
				//
				for (int j = 0; j < targetsSize; j++) {
					tokensIterator.previous();
				}
			}
		}

		return false;
	}

	/**
	 * The wild card form of {@link #test(List, boolean, boolean)}. One value of a
	 * compact event can hold several analyzed tokens: the encoder writes an IPv4
	 * address or a Windows path as one value when its {@code ipv4SingleToken} or
	 * {@code windowsPathSingleToken} option is on (`C:\Program Files\App\app.exe`
	 * analyzes to five tokens), and an analyzer splits some single values
	 * (`PROGRA~1`). So each {@link L1esConsts#VARIABLE_TOKEN} stands for a run of
	 * zero or more tokens of {@link #targets}, and the phrase may start or end inside
	 * such a run. Returns true when the targets can be laid on a contiguous stretch
	 * of {@code tokens} this way, with at least one target matched by a token that
	 * is not a wild card unless {@code allowFullWildcardMatch}. A token of
	 * {@link AnalysisUtil#tokenizePatternWithEdges} marked open on a side matches
	 * a target it is only the start, end or a part of
	 * ({@link AnalysisUtil#tokenMatches}).
	 *
	 * Runs over the pattern once per target: O(tokens x targets).
	 */
	private boolean testWithVariables(List<String> tokens, boolean allowFullWildcardMatch) {
		int size = tokens.size();
		int targetsSize = targets.size();

		String[] pattern = tokens.toArray(new String[size]);
		boolean[] isVariable = new boolean[size];

		for (int i = 0; i < size; i++) {
			isVariable[i] = L1esConsts.VARIABLE_TOKEN.equals(pattern[i]);
		}

		// reach[i][s]: the targets matched so far end with the pattern at position i,
		// s = 1 once one of them was matched by a token that is not a wild card. A
		// stretch may start at any position.
		boolean[][] reach = new boolean[size + 1][2];

		for (int i = 0; i <= size; i++) {
			reach[i][0] = true;
		}

		for (int t = 0; t < targetsSize; t++) {
			String target = targets.get(t);
			boolean[][] next = new boolean[size + 1][2];
			boolean any = false;

			for (int i = 0; i < size; i++) {
				for (int s = 0; s < 2; s++) {
					if (!reach[i][s]) {
						continue;
					}

					if (isVariable[i]) {
						// the value's run takes this target and may take more
						next[i][s] = true;
						any = true;
					} else if (AnalysisUtil.tokenMatches(pattern[i], target)) {
						next[i + 1][1] = true;
						any = true;
					}
				}
			}

			if (!any) {
				return false;
			}

			// a run may end anywhere: from a wild card, go on past it
			for (int i = 0; i < size; i++) {
				if (isVariable[i]) {
					next[i + 1][0] |= next[i][0];
					next[i + 1][1] |= next[i][1];
				}
			}

			reach = next;
		}

		for (int i = 0; i <= size; i++) {
			if (reach[i][1] || (allowFullWildcardMatch && reach[i][0])) {
				return true;
			}
		}

		return false;
	}
}
