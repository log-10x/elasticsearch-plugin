package com.log10x.l1es.query.predicate;

import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Strings;

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
	 * @param allowVariableWildcard  When set to {@code true}, will treat
	 *                               {@link L1esConsts#VARIABLE_TOKEN} in
	 *                               {@code tokens} as wild cards that can match any
	 *                               token in {@link #targets}
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
}
