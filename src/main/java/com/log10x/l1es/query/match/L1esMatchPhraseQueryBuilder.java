package com.log10x.l1es.query.match;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.search.MatchQueryParser;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.plugin.log10x.config.FlagsConfig;
import org.elasticsearch.plugins.SearchPlugin.QuerySpec;

import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.query.QueryUtil;
import com.log10x.l1es.query.factoy.L1esQuerySpecFactory;
import com.log10x.l1es.query.match.query.L1esMatchQuery;

/**
 * A class for creating L1es backed "match_phrase" queries.
 */
public class L1esMatchPhraseQueryBuilder extends MatchPhraseQueryBuilder {
	private static final Logger logger = LogManager.getLogger(L1esMatchPhraseQueryBuilder.class);

	public static final String NAME = QueryUtil.l1esQueryName(MatchPhraseQueryBuilder.NAME);

	private final Client client;

	public L1esMatchPhraseQueryBuilder(String fieldName, Object value, Client client) {
		super(fieldName, value);
		this.client = client;
	}

	public L1esMatchPhraseQueryBuilder(StreamInput in, Client client) throws IOException {
		super(in);
		this.client = client;
	}

	@Override
	public String getWriteableName() {
		return NAME;
	}

	@Override
	public void doXContent(XContentBuilder builder, Params params) throws IOException {
		builder.startObject(NAME);
		builder.startObject(fieldName());

		builder.field(MatchQueryBuilder.QUERY_FIELD.getPreferredName(), value());

		if (analyzer() != null) {
			builder.field(MatchQueryBuilder.ANALYZER_FIELD.getPreferredName(), analyzer());
		}

		builder.field(SLOP_FIELD.getPreferredName(), slop());
		builder.field(ZERO_TERMS_QUERY_FIELD.getPreferredName(), zeroTermsQuery().toString());
		printBoostAndQueryName(builder);
		builder.endObject();
		builder.endObject();
	}

	@Override
	protected Query doToQuery(SearchExecutionContext context) throws IOException {
		if (!L1esConfig.get().flags.matchPharseQueryEnabled()) {
			return super.doToQuery(context);
		}

		if (client == null) {
			logger.warn("Missing client, falling back to default match phrase query builder.");
			return super.doToQuery(context);
		}

		if (analyzer() != null && context.getIndexAnalyzers().get(analyzer()) == null) {
			throw new QueryShardException(context, "[" + NAME + "] analyzer [" + analyzer() + "] not found");
		}

		MatchQueryParser queryParser = new L1esMatchQuery(context, client, DmlDB.of(client));

		if (analyzer() != null) {
			queryParser.setAnalyzer(analyzer());
		}

		queryParser.setPhraseSlop(slop());
		queryParser.setZeroTermsQuery(zeroTermsQuery());

		return queryParser.parse(MatchQueryParser.Type.PHRASE, fieldName(), value());
	}

	/**
	 * Creates a new {@link L1esMatchPhraseQueryBuilder} from a standard
	 * {@link MatchPhraseQueryBuilder}, copying all parameters.
	 */
	public static L1esMatchPhraseQueryBuilder fromStandard(MatchPhraseQueryBuilder matchPhraseQueryBuilder,
			Client client) {
		L1esMatchPhraseQueryBuilder l1esMatchQuery = new L1esMatchPhraseQueryBuilder(
				matchPhraseQueryBuilder.fieldName(), matchPhraseQueryBuilder.value(), client);

		l1esMatchQuery.analyzer(matchPhraseQueryBuilder.analyzer());
		l1esMatchQuery.slop(matchPhraseQueryBuilder.slop());
		l1esMatchQuery.zeroTermsQuery(matchPhraseQueryBuilder.zeroTermsQuery());
		l1esMatchQuery.queryName(matchPhraseQueryBuilder.queryName());
		l1esMatchQuery.boost(matchPhraseQueryBuilder.boost());

		return l1esMatchQuery;
	}

	public static L1esMatchPhraseQueryBuilder fromXContent(XContentParser parser, Client client) throws IOException {
		MatchPhraseQueryBuilder matchPhraseQueryBuilder = MatchPhraseQueryBuilder.fromXContent(parser);

		L1esMatchPhraseQueryBuilder l1esMatchQuery = new L1esMatchPhraseQueryBuilder(
				matchPhraseQueryBuilder.fieldName(), matchPhraseQueryBuilder.value(), client);

		l1esMatchQuery.analyzer(matchPhraseQueryBuilder.analyzer());
		l1esMatchQuery.slop(matchPhraseQueryBuilder.slop());
		l1esMatchQuery.zeroTermsQuery(matchPhraseQueryBuilder.zeroTermsQuery());
		l1esMatchQuery.queryName(matchPhraseQueryBuilder.queryName());
		l1esMatchQuery.boost(matchPhraseQueryBuilder.boost());

		return l1esMatchQuery;
	}

	public static class Factory extends L1esQuerySpecFactory<L1esMatchPhraseQueryBuilder> {
		public static final Factory Instance = new Factory();

		@Override
		public String queryName() {
			return L1esMatchPhraseQueryBuilder.NAME;
		}

		@Override
		protected L1esMatchPhraseQueryBuilder newQueryBuilder(StreamInput in) throws IOException {
			return new L1esMatchPhraseQueryBuilder(in, client);
		}

		@Override
		protected L1esMatchPhraseQueryBuilder newQueryBuilder(XContentParser parser) throws IOException {
			return L1esMatchPhraseQueryBuilder.fromXContent(parser, client);
		}
	}
}
