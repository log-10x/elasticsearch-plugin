package com.log10x.l1es.query.match;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.query.support.QueryParsers;
import org.elasticsearch.index.search.MatchQueryParser;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.plugin.log10x.config.FlagsConfig;
import org.elasticsearch.plugins.SearchPlugin.QuerySpec;

import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.query.QueryUtil;
import com.log10x.l1es.query.factoy.L1esQuerySpecFactory;
import com.log10x.l1es.query.match.query.L1esMatchQuery;

/**
 * A class for creating L1es backed "match" queries.
 */
public class L1esMatchQueryBuilder extends MatchQueryBuilder {
	private static final Logger logger = LogManager.getLogger(L1esMatchQueryBuilder.class);

	public static final String NAME = QueryUtil.l1esQueryName(MatchQueryBuilder.NAME);

	private final Client client;

	public L1esMatchQueryBuilder(String fieldName, Object value, Client client) {
		super(fieldName, value);
		this.client = client;
	}

	public L1esMatchQueryBuilder(StreamInput in, Client client) throws IOException {
		super(in);
		this.client = client;
	}

	@Override
	public String getWriteableName() {
		return NAME;
	}

	@SuppressWarnings("deprecation")
	@Override
	public void doXContent(XContentBuilder builder, Params params) throws IOException {
		builder.startObject(NAME);
		builder.startObject(fieldName());

		builder.field(QUERY_FIELD.getPreferredName(), value());
		builder.field(OPERATOR_FIELD.getPreferredName(), operator().toString());

		if (analyzer() != null) {
			builder.field(ANALYZER_FIELD.getPreferredName(), analyzer());
		}

		if (fuzziness() != null) {
			fuzziness().toXContent(builder, params);
		}

		builder.field(PREFIX_LENGTH_FIELD.getPreferredName(), prefixLength());
		builder.field(MAX_EXPANSIONS_FIELD.getPreferredName(), maxExpansions());

		if (minimumShouldMatch() != null) {
			builder.field(MINIMUM_SHOULD_MATCH_FIELD.getPreferredName(), minimumShouldMatch());
		}

		if (fuzzyRewrite() != null) {
			builder.field(FUZZY_REWRITE_FIELD.getPreferredName(), fuzzyRewrite());
		}

		builder.field(FUZZY_TRANSPOSITIONS_FIELD.getPreferredName(), fuzzyTranspositions());
		builder.field(LENIENT_FIELD.getPreferredName(), lenient());
		builder.field(ZERO_TERMS_QUERY_FIELD.getPreferredName(), zeroTermsQuery().toString());

		builder.field(GENERATE_SYNONYMS_PHRASE_QUERY.getPreferredName(), autoGenerateSynonymsPhraseQuery());
		printBoostAndQueryName(builder);

		builder.endObject();
		builder.endObject();
	}

	@Override
	protected Query doToQuery(SearchExecutionContext context) throws IOException {
		if (!L1esConfig.get().flags.matchQueryEnabled()) {
			return super.doToQuery(context);
		}

		if (client == null) {
			logger.warn("Missing client, falling back to default match query builder.");
			return super.doToQuery(context);
		}

		if (analyzer() != null && context.getIndexAnalyzers().get(analyzer()) == null) {
			throw new QueryShardException(context, "[" + NAME + "] analyzer [" + analyzer() + "] not found");
		}

		L1esMatchQuery queryParser = new L1esMatchQuery(context, client, DmlDB.of(client));

		queryParser.setOccur(operator().toBooleanClauseOccur());

		if (analyzer() != null) {
			queryParser.setAnalyzer(analyzer());
		}

		queryParser.setFuzziness(fuzziness());
		queryParser.setFuzzyPrefixLength(prefixLength());
		queryParser.setMaxExpansions(maxExpansions());
		queryParser.setTranspositions(fuzzyTranspositions());
		queryParser.setFuzzyRewriteMethod(
				QueryParsers.parseRewriteMethod(fuzzyRewrite(), null, LoggingDeprecationHandler.INSTANCE));
		queryParser.setLenient(lenient());
		queryParser.setZeroTermsQuery(zeroTermsQuery());
		queryParser.setAutoGenerateSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery());

		Query query = queryParser.parse(MatchQueryParser.Type.BOOLEAN, fieldName(), value());

		return QueryUtil.maybeApplyMinimumShouldMatch(query, minimumShouldMatch());
	}

	/**
	 * Creates a new {@link L1esMatchQueryBuilder} from a standard {@link MatchQueryBuilder},
	 * copying all parameters.
	 */
	public static L1esMatchQueryBuilder fromStandard(MatchQueryBuilder matchQueryBuilder, Client client) {
		L1esMatchQueryBuilder l1esMatchQuery = new L1esMatchQueryBuilder(matchQueryBuilder.fieldName(),
				matchQueryBuilder.value(), client);

		l1esMatchQuery.operator(matchQueryBuilder.operator());
		l1esMatchQuery.analyzer(matchQueryBuilder.analyzer());
		l1esMatchQuery.minimumShouldMatch(matchQueryBuilder.minimumShouldMatch());

		if (matchQueryBuilder.fuzziness() != null) {
			l1esMatchQuery.fuzziness(matchQueryBuilder.fuzziness());
		}

		l1esMatchQuery.fuzzyRewrite(matchQueryBuilder.fuzzyRewrite());
		l1esMatchQuery.prefixLength(matchQueryBuilder.prefixLength());
		l1esMatchQuery.fuzzyTranspositions(matchQueryBuilder.fuzzyTranspositions());
		l1esMatchQuery.maxExpansions(matchQueryBuilder.maxExpansions());
		l1esMatchQuery.lenient(matchQueryBuilder.lenient());
		l1esMatchQuery.zeroTermsQuery(matchQueryBuilder.zeroTermsQuery());
		l1esMatchQuery.autoGenerateSynonymsPhraseQuery(matchQueryBuilder.autoGenerateSynonymsPhraseQuery());
		l1esMatchQuery.queryName(matchQueryBuilder.queryName());
		l1esMatchQuery.boost(matchQueryBuilder.boost());

		return l1esMatchQuery;
	}

	public static L1esMatchQueryBuilder fromXContent(XContentParser parser, Client client) throws IOException {
		MatchQueryBuilder matchQueryBuilder = MatchQueryBuilder.fromXContent(parser);

		L1esMatchQueryBuilder l1esMatchQuery = new L1esMatchQueryBuilder(matchQueryBuilder.fieldName(),
				matchQueryBuilder.value(), client);

		l1esMatchQuery.operator(matchQueryBuilder.operator());
		l1esMatchQuery.analyzer(matchQueryBuilder.analyzer());
		l1esMatchQuery.minimumShouldMatch(matchQueryBuilder.minimumShouldMatch());

		if (matchQueryBuilder.fuzziness() != null) {
			l1esMatchQuery.fuzziness(matchQueryBuilder.fuzziness());
		}

		l1esMatchQuery.fuzzyRewrite(matchQueryBuilder.fuzzyRewrite());
		l1esMatchQuery.prefixLength(matchQueryBuilder.prefixLength());
		l1esMatchQuery.fuzzyTranspositions(matchQueryBuilder.fuzzyTranspositions());
		l1esMatchQuery.maxExpansions(matchQueryBuilder.maxExpansions());
		l1esMatchQuery.lenient(matchQueryBuilder.lenient());
		l1esMatchQuery.zeroTermsQuery(matchQueryBuilder.zeroTermsQuery());
		l1esMatchQuery.autoGenerateSynonymsPhraseQuery(matchQueryBuilder.autoGenerateSynonymsPhraseQuery());
		l1esMatchQuery.queryName(matchQueryBuilder.queryName());
		l1esMatchQuery.boost(matchQueryBuilder.boost());

		return l1esMatchQuery;
	}

	public static class Factory extends L1esQuerySpecFactory<L1esMatchQueryBuilder> {
		public static final Factory Instance = new Factory();

		@Override
		public String queryName() {
			return L1esMatchQueryBuilder.NAME;
		}

		@Override
		protected L1esMatchQueryBuilder newQueryBuilder(StreamInput in) throws IOException {
			return new L1esMatchQueryBuilder(in, client);
		}

		@Override
		protected L1esMatchQueryBuilder newQueryBuilder(XContentParser parser) throws IOException {
			return L1esMatchQueryBuilder.fromXContent(parser, client);
		}
	}
}
