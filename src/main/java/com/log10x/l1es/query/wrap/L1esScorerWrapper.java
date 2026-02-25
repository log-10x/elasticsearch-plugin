package com.log10x.l1es.query.wrap;

import java.io.IOException;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

import com.log10x.l1es.dml.DmlUtil;
import com.log10x.l1es.query.wrap.L1esQueryWrapper.L1esQueryWrapperData;
import com.log10x.l1es.util.ESUtil;

/**
 * A wrapper class for another {@link Scorer} which acts as a filter layer.
 * 
 * Used when scoring documents based on L1x encoded fields where it's possible
 * that the actual decoded value of the field won't match at all to the original
 * user query.
 * 
 * Works by decoding the field and testing it against a {@link Predicate} found
 * in {@link L1esQueryWrapperData#crossChecker}
 * 
 * Any failure while testing will result in a "pass" result, meaning the user
 * might get results that don't match, but that's probably preferable to not
 * getting results that do match.
 * 
 * Implementation is based on MinScoreScorer
 */
public class L1esScorerWrapper extends Scorer {
	private static final Logger logger = LogManager.getLogger(L1esScorerWrapper.class);

	private final Scorer internal;
	private final LeafReaderContext context;
	private final L1esQueryWrapperData wrapperData;
	private final SortedDocValues tidDocValues;

	/**
	 * Ordinal-based classification cache. Avoids repeated lookupOrd() +
	 * utf8ToString() + HashMap.get() for docs sharing the same template.
	 * Values: 0=unclassified, 1=MUST_MATCH, 2=CANNOT_MATCH, 3=MIGHT_MATCH.
	 */
	private byte[] classificationByOrd;

	private float curScore;

	private L1esScorerWrapper(Weight weight, Scorer internal, LeafReaderContext context,
			L1esQueryWrapperData wrapperData) {
		super(weight);

		this.internal = internal;

		this.context = context;
		this.wrapperData = wrapperData;

		SortedDocValues dv = null;
		if (wrapperData.tidFieldName != null) {
			try {
				dv = context.reader().getSortedDocValues(wrapperData.tidFieldName);
			} catch (Exception e) {
				logger.warn("Failed getting SortedDocValues for {}, falling back to _source.",
						wrapperData.tidFieldName, e);
			}
		}
		this.tidDocValues = dv;
	}

	@Override
	public int docID() {
		return internal.docID();
	}

	@Override
	public float score() throws IOException {
		// Similar logic to that of MinScoreScorer.
		//
		return curScore;
	}

	@Override
	public int advanceShallow(int target) throws IOException {
		return internal.advanceShallow(target);
	}

	@Override
	public float getMaxScore(int upTo) throws IOException {
		return internal.getMaxScore(upTo);
	}

	@Override
	public DocIdSetIterator iterator() {
		return TwoPhaseIterator.asDocIdSetIterator(twoPhaseIterator());
	}

	@Override
	public TwoPhaseIterator twoPhaseIterator() {
		final TwoPhaseIterator inTwoPhase = this.internal.twoPhaseIterator();

		final DocIdSetIterator approximation = (inTwoPhase == null) ? this.internal.iterator()
				: inTwoPhase.approximation();

		return new TwoPhaseIterator(approximation) {
			@Override
			public boolean matches() throws IOException {

				if ((inTwoPhase != null) && (!inTwoPhase.matches())) {

					// No need to check further.
					//
					return false;
				}

				if (!testDocument(docID())) {
					return false;
				}

				// Score after testDocument to skip computation for rejected docs.
				//
				curScore = internal.score();

				return true;
			}

			private boolean testDocument(int id) {
				try {
					if (tidDocValues != null) {
						return testDocumentViaTid(id);
					} else {
						return testDocumentViaSource(id);
					}
				} catch (Exception e) {
					logger.error("Error while checking document {}.", id, e);

					return true;
				}
			}

			/**
			 * Fast path: read template ID from SortedDocValues on l1x_tid.
			 * Uses ordinal-based classification cache to avoid repeated
			 * lookupOrd + utf8ToString + HashMap.get for the same template.
			 */
			private boolean testDocumentViaTid(int id) throws IOException {
				if (!tidDocValues.advanceExact(id)) {
					return testDocumentViaSource(id);
				}

				int ord = tidDocValues.ordValue();

				// Lazy-init ordinal cache on first use per segment.
				if (classificationByOrd == null) {
					classificationByOrd = new byte[(int) tidDocValues.getValueCount()];
				}

				byte cached = classificationByOrd[ord];

				if (cached != 0) {
					// Cache hit: skip lookupOrd + utf8ToString + HashMap.get.
					if (cached == 1) return true;   // MUST_MATCH
					if (cached == 2) return false;   // CANNOT_MATCH
					// cached == 3: MIGHT_MATCH — fall through to decode.
				} else {
					// Cache miss: classify via string lookup, then cache.
					String patternId = tidDocValues.lookupOrd(ord).utf8ToString();

					if (patternId == null || patternId.isEmpty()) {
						classificationByOrd[ord] = 3;
						return testDocumentViaSource(id);
					}

					// Try templateClassifier first (lazy classification).
					if (wrapperData.templateClassifier != null) {
						byte classification = wrapperData.templateClassifier.classify(patternId);
						classificationByOrd[ord] = classification;
						if (classification == 1) return true;  // MUST_MATCH
						if (classification == 2) return false; // CANNOT_MATCH
						// classification == 3: MIGHT_MATCH — fall through to decode.
					} else if (wrapperData.templateMustMatch != null) {
						Boolean mustMatch = wrapperData.templateMustMatch.get(patternId);

						if (mustMatch != null) {
							if (mustMatch) {
								classificationByOrd[ord] = 1;
								return true;
							} else {
								classificationByOrd[ord] = 2;
								return false;
							}
						} else if (wrapperData.templateMustMatch.containsKey(patternId)) {
							// In map with null value: explicit MIGHT_MATCH.
							// Fall through to decode path below.
						} else if (wrapperData.defaultCannotMatch) {
							// Not in map + defaultCannotMatch: treat as CANNOT.
							classificationByOrd[ord] = 2;
							return false;
						}
					}

					classificationByOrd[ord] = 3; // MIGHT_MATCH
				}

				// MIGHT_MATCH: need _source for decode + crossCheck.
				Document document = ESUtil.safeDocument(id, context.reader());

				if (document == null) {
					logger.warn("Got no document for {}.", id);
					return true;
				}

				Object field = ESUtil.getDocumentFieldStreaming(document, wrapperData.fieldName,
						Integer.toString(id));

				if (field == null) {
					logger.warn("Failed getting field {} of {}.", wrapperData.fieldName, id);
					return true;
				}

				Object decodedField = DmlUtil.decode(field, wrapperData.dmlDB);

				if (decodedField == null) {
					logger.warn("Failed getting decoded field {} of {}.", wrapperData.fieldName, id);
					return true;
				}

				if (!(decodedField instanceof String)) {
					logger.warn("Decoded field {} of {} isn't a String.", wrapperData.fieldName, id);
					return true;
				}

				boolean result = wrapperData.crossChecker.test((String) decodedField);
				logger.debug("Predicate result on {} is {}.", decodedField, result);
				return result;
			}

			/**
			 * Standard path: read template ID from _source.
			 */
			private boolean testDocumentViaSource(int id) throws IOException {
				Document document = ESUtil.safeDocument(id, context.reader());

				if (document == null) {
					logger.warn("Got no document for {}.", id);

					return true;
				}

				String fieldName = wrapperData.fieldName;

				Object field = ESUtil.getDocumentFieldStreaming(document, fieldName, Integer.toString(id));

				if (field == null) {
					logger.warn("Failed getting field {} of {}.", fieldName, id);

					return true;
				}

				// Template-level short-circuit using tri-state classification:
				// TRUE = MUST_MATCH, FALSE = CANNOT_MATCH, absent = MIGHT_MATCH
				if ((field instanceof String) && (wrapperData.templateMustMatch != null)) {
					String patternId = DmlUtil.extractPatternId((String) field);

					if (patternId != null) {
						Boolean mustMatch = wrapperData.templateMustMatch.get(patternId);

						if (Boolean.TRUE.equals(mustMatch)) {
							return true;
						}

						if (Boolean.FALSE.equals(mustMatch)) {
							return false;
						}
					}
				}

				Object decodedField = DmlUtil.decode(field, wrapperData.dmlDB);

				if (decodedField == null) {
					logger.warn("Failed getting decoded field {} of {}.", fieldName, id);

					return true;
				}

				if (!(decodedField instanceof String)) {
					logger.warn("Decoded field {} of {} isn't a String.", fieldName, id);

					return true;
				}

				boolean result = wrapperData.crossChecker.test((String) decodedField);

				logger.debug("Predicate result on {} is {}.", decodedField, result);

				return result;
			}

			@Override
			public float matchCost() {
				// Similar logic to that of MinScoreScorer.
				//
				return 1000f // random constant for the score computation
						+ (inTwoPhase == null ? 0 : inTwoPhase.matchCost());
			}

		};
	}

	/**
	 * Wraps a given {@link Scorer} with a {@code L1esScorerWrapper}
	 * 
	 * @param weight      The {@link Weight} to be used by the scorer.
	 * @param internal    The {@link Scorer} to wrap.
	 * @param context     The {@link LeafReaderContext} to be used by the scorer.
	 * @param wrapperData additional {@link L1esQueryWrapperData} to use.
	 * 
	 * @return a new {@code L1esScorerWrapper}
	 */
	public static Scorer wrap(Weight weight, Scorer internal, LeafReaderContext context,
			L1esQueryWrapperData wrapperData) {
		return new L1esScorerWrapper(weight, internal, context, wrapperData);
	}
}
