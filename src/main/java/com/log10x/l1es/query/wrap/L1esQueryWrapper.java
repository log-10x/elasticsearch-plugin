package com.log10x.l1es.query.wrap;

import java.io.IOException;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;

import com.log10x.l1es.dml.DmlDB;

/**
 * A wrapper class for another {@link Query}, who's sole purpose is to wrap that
 * {@link Query#createWeight} method with an {@link L1esWeightWrapper}
 */
public class L1esQueryWrapper extends Query {
	private final Query internal;
	private final L1esQueryWrapperData wrapperData;

	private L1esQueryWrapper(Query internal, L1esQueryWrapperData wrapperData) {
		this.internal = internal;
		this.wrapperData = wrapperData;
	}

	public Query getInternal() {
		return internal;
	}

	public Query withNewInternal(Query newInternal) {
		return wrap(newInternal, wrapperData);
	}

	@Override
	public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
		return L1esWeightWrapper.wrap(this, internal.createWeight(searcher, scoreMode, boost), wrapperData);
	}

	@Override
	public Query rewrite(IndexSearcher indexSearcher) throws IOException {
		Query rewrittenInternal = internal.rewrite(indexSearcher);

		if (rewrittenInternal == internal) {
			return this;
		}

		return new L1esQueryWrapper(rewrittenInternal, wrapperData);
	}

	@Override
	public void visit(QueryVisitor visitor) {
		internal.visit(visitor);
	}

	@Override
	public String toString(String field) {
		return internal.toString(field);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this) {
			return true;
		}

		if (!(obj instanceof L1esQueryWrapper)) {
			return false;
		}

		L1esQueryWrapper other = (L1esQueryWrapper) obj;

		return internal.equals(other.internal);
	}

	@Override
	public int hashCode() {
		return internal.hashCode() ^ classHash();
	}

	public static Query wrap(Query internal, String fieldName, DmlDB dmlDB, Predicate<String> crossChecker) {
		return wrap(internal, fieldName, dmlDB, crossChecker, null);
	}

	public static Query wrap(Query internal, String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
			Map<String, Boolean> templateMustMatch) {
		return wrap(internal, L1esQueryWrapperData.of(fieldName, dmlDB, crossChecker, templateMustMatch));
	}

	public static Query wrap(Query internal, String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
			Map<String, Boolean> templateMustMatch, String tidFieldName) {
		return wrap(internal, L1esQueryWrapperData.of(fieldName, dmlDB, crossChecker, templateMustMatch, tidFieldName, false));
	}

	public static Query wrap(Query internal, String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
			Map<String, Boolean> templateMustMatch, String tidFieldName, boolean defaultCannotMatch) {
		return wrap(internal, L1esQueryWrapperData.of(fieldName, dmlDB, crossChecker, templateMustMatch, tidFieldName, defaultCannotMatch));
	}

	public static Query wrap(Query internal, String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
			Map<String, Boolean> templateMustMatch, String tidFieldName,
			TemplateClassifier templateClassifier) {
		return wrap(internal, L1esQueryWrapperData.of(fieldName, dmlDB, crossChecker, templateMustMatch, tidFieldName, templateClassifier));
	}

	public static Query wrap(Query internal, L1esQueryWrapperData wrapperData) {
		return new L1esQueryWrapper(internal, wrapperData);
	}

	/**
	 * Classifies a template by ID. Returns 1=MUST_MATCH, 2=CANNOT_MATCH, 3=MIGHT_MATCH.
	 * Used for lazy classification in the scorer's ordinal cache.
	 */
	@FunctionalInterface
	public interface TemplateClassifier {
		byte classify(String patternId);
	}

	static class L1esQueryWrapperData {
		final String fieldName;
		final DmlDB dmlDB;
		final Predicate<String> crossChecker;
		final Map<String, Boolean> templateMustMatch;
		final String tidFieldName;
		final boolean defaultCannotMatch;
		final TemplateClassifier templateClassifier;

		private L1esQueryWrapperData(String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
				Map<String, Boolean> templateMustMatch, String tidFieldName, boolean defaultCannotMatch,
				TemplateClassifier templateClassifier) {
			this.fieldName = fieldName;
			this.dmlDB = dmlDB;
			this.crossChecker = crossChecker;
			this.templateMustMatch = templateMustMatch;
			this.tidFieldName = tidFieldName;
			this.defaultCannotMatch = defaultCannotMatch;
			this.templateClassifier = templateClassifier;
		}

		static L1esQueryWrapperData of(String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
				Map<String, Boolean> templateMustMatch) {
			return new L1esQueryWrapperData(fieldName, dmlDB, crossChecker, templateMustMatch, null, false, null);
		}

		static L1esQueryWrapperData of(String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
				Map<String, Boolean> templateMustMatch, String tidFieldName, boolean defaultCannotMatch) {
			return new L1esQueryWrapperData(fieldName, dmlDB, crossChecker, templateMustMatch, tidFieldName, defaultCannotMatch, null);
		}

		static L1esQueryWrapperData of(String fieldName, DmlDB dmlDB, Predicate<String> crossChecker,
				Map<String, Boolean> templateMustMatch, String tidFieldName,
				TemplateClassifier templateClassifier) {
			return new L1esQueryWrapperData(fieldName, dmlDB, crossChecker, templateMustMatch, tidFieldName, false, templateClassifier);
		}
	}
}
