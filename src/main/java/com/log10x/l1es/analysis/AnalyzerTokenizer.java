package com.log10x.l1es.analysis;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.core.IOUtils;

/**
 * Wrapper class for {@link Analyzer} and a field name used for easy
 * toknenization of values.
 *
 */
public class AnalyzerTokenizer {
	private static final Logger logger = LogManager.getLogger(AnalyzerTokenizer.class);

	private final Analyzer originalAnalyzer;
	private final String fieldName;

	/**
	 * @param originalAnalyzer Internal {@link Analyzer} to tokenize with.
	 * @param fieldName        Name of the field tokenization is for.
	 * 
	 */
	private AnalyzerTokenizer(Analyzer originalAnalyzer, String fieldName) {
		this.originalAnalyzer = originalAnalyzer;
		this.fieldName = fieldName;
	}

	/**
	 * Tokenizes a given text with the internal {@link Analyzer} and returns the
	 * resulting tokens, in Set format.
	 * 
	 * @param text Input text to tokenize.
	 * 
	 * @return {@link Set} of the resulting tokens.
	 */
	public Set<String> tokenSet(String text) {
		return tokenize(text, new HashSet<>());
	}

	/**
	 * Tokenizes a given text with the internal {@link Analyzer} and returns the
	 * resulting tokens, in List format.
	 * 
	 * @param text Input text to tokenize.
	 * 
	 * @return {@link List} of the resulting tokens.
	 */
	public List<String> tokenList(String text) {
		return tokenize(text, new java.util.ArrayList<>());
	}

	/**
	 * Tokenizes a given text with the internal {@link Analyzer} and fills the given
	 * collection with the results.
	 * 
	 * @param <T>        class that extends Collection&lt;String&gt;
	 * @param text       Input text to tokenize.
	 * @param collection Collection to fill with tokens.
	 * 
	 * @return the given collection.
	 */
	public <T extends Collection<String>> T tokenize(String text, T collection) {
		try {
			TokenStream stream = originalAnalyzer.tokenStream(fieldName, text);

			if (stream == null) {
				logger.warn("No token stream for match on {}.", fieldName);
				return collection;
			}

			try {
				stream.reset();

				CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);

				while (stream.incrementToken()) {
					collection.add(term.toString());
				}

				stream.end();
			} finally {
				IOUtils.closeWhileHandlingException(stream);
			}

			return collection;
		} catch (Exception e) {
			logger.error("Failed getting original tokens of {} for search on {}.", text, fieldName);
			return collection;
		}
	}

	/**
	 * Factory method for creating new {@link AnalyzerTokenizer}
	 * 
	 * @param originalAnalyzer Internal {@link Analyzer} to tokenize with.
	 * @param fieldName        Name of the field tokenization is for.
	 * 
	 * @return A new {@link AnalyzerTokenizer}
	 */
	public static AnalyzerTokenizer of(Analyzer originalAnalyzer, String fieldName) {
		return new AnalyzerTokenizer(originalAnalyzer, fieldName);
	}
}
