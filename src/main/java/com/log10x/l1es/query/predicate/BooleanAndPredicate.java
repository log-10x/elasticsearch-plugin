package com.log10x.l1es.query.predicate;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.index.query.Operator;

import com.log10x.l1es.analysis.AnalyzerTokenizer;
import com.log10x.l1es.query.match.query.L1esMatchQuery;

/**
 * Class for testing whether a {@link String} contains all of the tokens in a
 * given {@code Set<String>}.
 * 
 * Used as a cross checker for "match" queries with {@link Operator#AND}.
 * 
 * See {@link L1esMatchQuery#l1esBooleanAndQuery}
 */
public class BooleanAndPredicate implements Predicate<String> {
	private static final Logger logger = LogManager.getLogger(BooleanAndPredicate.class);

	private final AnalyzerTokenizer analyzerTokenizer;
	private final Collection<String> targets;

	/**
	 * @param analyzerTokenizer analyzer responsible for tokenizing test tokens.
	 * @param targets           the targets to match to.
	 */
	public BooleanAndPredicate(AnalyzerTokenizer analyzerTokenizer, Set<String> targets) {
		this.analyzerTokenizer = analyzerTokenizer;
		this.targets = targets;
	}

	/**
	 * Tests the input {@link String} {@code t} against the internal
	 * {@link #targets}.
	 * 
	 * Uses {@link #analyzerTokenizer} to tokenize {@code t} and then checks if all
	 * of the tokens in {@link #targets} are present.
	 */
	@Override
	public boolean test(String t) {
		try {
			Collection<String> tokens = analyzerTokenizer.tokenSet(t);

			if (tokens.isEmpty()) {
				return targets.isEmpty();
			}

			// Fast reject: if fewer tokens than targets, containsAll is impossible.
			if (tokens.size() < targets.size()) {
				return false;
			}

			for (String target : targets) {
				if (!tokens.contains(target)) {
					return false;
				}
			}

			return true;
		} catch (Exception e) {
			logger.error("Failed checking on \"{}\"", t, e);
			return true;
		}
	}
}
