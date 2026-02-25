package com.log10x.l1es.query.wrap;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;

import com.log10x.l1es.query.wrap.L1esQueryWrapper.L1esQueryWrapperData;

/**
 * A wrapper class for another {@link Weight}, who's sole purpose is to wrap
 * that {@link Weight#scorer} method with an {@link L1esScorerWrapper}
 */
public class L1esWeightWrapper extends Weight {
	private final Weight internal;
	private final L1esQueryWrapperData wrapperData;

	private L1esWeightWrapper(Query query, Weight internal, L1esQueryWrapperData wrapperData) {
		super(query);

		this.internal = internal;
		this.wrapperData = wrapperData;
	}

	@Override
	public boolean isCacheable(LeafReaderContext ctx) {
		return internal.isCacheable(ctx);
	}

	@Override
	public Explanation explain(LeafReaderContext context, int doc) throws IOException {
		return internal.explain(context, doc);
	}

	@Override
	public Scorer scorer(LeafReaderContext context) throws IOException {
		Scorer internalScorer = internal.scorer(context);

		if (internalScorer == null) {
			return null;
		}

		return L1esScorerWrapper.wrap(this, internalScorer, context, wrapperData);
	}

	public static Weight wrap(Query query, Weight internal, L1esQueryWrapperData wrapperData) {
		return new L1esWeightWrapper(query, internal, wrapperData);
	}
}
