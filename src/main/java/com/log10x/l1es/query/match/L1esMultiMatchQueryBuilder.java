package com.log10x.l1es.query.match;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentParseException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.query.ZeroTermsQueryOption;
import org.elasticsearch.index.query.support.QueryParsers;
import org.elasticsearch.index.search.MatchQueryParser;
import org.elasticsearch.index.search.QueryParserHelper;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.plugin.log10x.config.FlagsConfig;
import org.elasticsearch.plugins.SearchPlugin.QuerySpec;

import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.query.QueryUtil;
import com.log10x.l1es.query.factoy.L1esQuerySpecFactory;
import com.log10x.l1es.query.match.query.L1esMultiMatchQuery;

/**
 * A class for creating L1es backed "multi_match" queries.
 *
 * In ES 8.x, MultiMatchQueryBuilder is final and cannot be extended.
 * This class uses AbstractQueryBuilder as a base and delegates to an internal
 * MultiMatchQueryBuilder for standard functionality.
 */
public class L1esMultiMatchQueryBuilder extends AbstractQueryBuilder<L1esMultiMatchQueryBuilder> {
	private static final ParseField SLOP_FIELD = new ParseField("slop");
	private static final ParseField ZERO_TERMS_QUERY_FIELD = new ParseField("zero_terms_query");
	private static final ParseField LENIENT_FIELD = new ParseField("lenient");
	private static final ParseField TIE_BREAKER_FIELD = new ParseField("tie_breaker");
	private static final ParseField FUZZY_REWRITE_FIELD = new ParseField("fuzzy_rewrite");
	private static final ParseField MINIMUM_SHOULD_MATCH_FIELD = new ParseField("minimum_should_match");
	private static final ParseField OPERATOR_FIELD = new ParseField("operator");
	private static final ParseField MAX_EXPANSIONS_FIELD = new ParseField("max_expansions");
	private static final ParseField PREFIX_LENGTH_FIELD = new ParseField("prefix_length");
	private static final ParseField ANALYZER_FIELD = new ParseField("analyzer");
	private static final ParseField TYPE_FIELD = new ParseField("type");
	private static final ParseField QUERY_FIELD = new ParseField("query");
	private static final ParseField FIELDS_FIELD = new ParseField("fields");
	private static final ParseField GENERATE_SYNONYMS_PHRASE_QUERY = new ParseField(
			"auto_generate_synonyms_phrase_query");
	private static final ParseField FUZZY_TRANSPOSITIONS_FIELD = new ParseField("fuzzy_transpositions");

	private static final Logger logger = LogManager.getLogger(L1esMultiMatchQueryBuilder.class);

	public static final String NAME = QueryUtil.l1esQueryName(MultiMatchQueryBuilder.NAME);

	private final Client client;
	private final Object value;
	private Map<String, Float> fieldsBoosts = new HashMap<>();
	private MultiMatchQueryBuilder.Type type = MultiMatchQueryBuilder.Type.BEST_FIELDS;
	private Operator operator = Operator.OR;
	private String analyzer;
	private int slop = 0;
	private Fuzziness fuzziness;
	private int prefixLength = 0;
	private int maxExpansions = 50;
	private String minimumShouldMatch;
	private String fuzzyRewrite;
	private Float tieBreaker;
	private Boolean lenient;
	private ZeroTermsQueryOption zeroTermsQuery = ZeroTermsQueryOption.NONE;
	private boolean autoGenerateSynonymsPhraseQuery = true;
	private boolean fuzzyTranspositions = true;

	public L1esMultiMatchQueryBuilder(Object value, Client client) {
		this.value = value;
		this.client = client;
	}

	public L1esMultiMatchQueryBuilder(StreamInput in, Client client) throws IOException {
		super(in);
		this.client = client;
		this.value = in.readGenericValue();
		int size = in.readVInt();
		this.fieldsBoosts = new HashMap<>(size);
		for (int i = 0; i < size; i++) {
			this.fieldsBoosts.put(in.readString(), in.readFloat());
		}
		this.type = MultiMatchQueryBuilder.Type.readFromStream(in);
		this.operator = Operator.readFromStream(in);
		this.analyzer = in.readOptionalString();
		this.slop = in.readVInt();
		this.fuzziness = in.readOptionalWriteable(Fuzziness::new);
		this.prefixLength = in.readVInt();
		this.maxExpansions = in.readVInt();
		this.minimumShouldMatch = in.readOptionalString();
		this.fuzzyRewrite = in.readOptionalString();
		this.tieBreaker = in.readOptionalFloat();
		this.lenient = in.readOptionalBoolean();
		this.zeroTermsQuery = ZeroTermsQueryOption.readFromStream(in);
		this.autoGenerateSynonymsPhraseQuery = in.readBoolean();
		this.fuzzyTranspositions = in.readBoolean();
	}

	@Override
	protected void doWriteTo(StreamOutput out) throws IOException {
		out.writeGenericValue(value);
		out.writeVInt(fieldsBoosts.size());
		for (Map.Entry<String, Float> entry : fieldsBoosts.entrySet()) {
			out.writeString(entry.getKey());
			out.writeFloat(entry.getValue());
		}
		type.writeTo(out);
		operator.writeTo(out);
		out.writeOptionalString(analyzer);
		out.writeVInt(slop);
		out.writeOptionalWriteable(fuzziness);
		out.writeVInt(prefixLength);
		out.writeVInt(maxExpansions);
		out.writeOptionalString(minimumShouldMatch);
		out.writeOptionalString(fuzzyRewrite);
		out.writeOptionalFloat(tieBreaker);
		out.writeOptionalBoolean(lenient);
		zeroTermsQuery.writeTo(out);
		out.writeBoolean(autoGenerateSynonymsPhraseQuery);
		out.writeBoolean(fuzzyTranspositions);
	}

	public Object value() { return value; }
	public Map<String, Float> fields() { return fieldsBoosts; }
	public MultiMatchQueryBuilder.Type type() { return type; }
	public Operator operator() { return operator; }
	public String analyzer() { return analyzer; }
	public int slop() { return slop; }
	public Fuzziness fuzziness() { return fuzziness; }
	public int prefixLength() { return prefixLength; }
	public int maxExpansions() { return maxExpansions; }
	public String minimumShouldMatch() { return minimumShouldMatch; }
	public String fuzzyRewrite() { return fuzzyRewrite; }
	public Float tieBreaker() { return tieBreaker; }
	public boolean lenient() { return lenient != null ? lenient : MatchQueryParser.DEFAULT_LENIENCY; }
	public ZeroTermsQueryOption zeroTermsQuery() { return zeroTermsQuery; }
	public boolean autoGenerateSynonymsPhraseQuery() { return autoGenerateSynonymsPhraseQuery; }
	public boolean fuzzyTranspositions() { return fuzzyTranspositions; }

	public L1esMultiMatchQueryBuilder fields(Map<String, Float> fields) { this.fieldsBoosts = fields; return this; }
	public L1esMultiMatchQueryBuilder type(MultiMatchQueryBuilder.Type type) { this.type = type; return this; }
	public L1esMultiMatchQueryBuilder operator(Operator operator) { this.operator = operator; return this; }
	public L1esMultiMatchQueryBuilder analyzer(String analyzer) { this.analyzer = analyzer; return this; }
	public L1esMultiMatchQueryBuilder slop(int slop) { this.slop = slop; return this; }
	public L1esMultiMatchQueryBuilder fuzziness(Fuzziness fuzziness) { this.fuzziness = fuzziness; return this; }
	public L1esMultiMatchQueryBuilder prefixLength(int prefixLength) { this.prefixLength = prefixLength; return this; }
	public L1esMultiMatchQueryBuilder maxExpansions(int maxExpansions) { this.maxExpansions = maxExpansions; return this; }
	public L1esMultiMatchQueryBuilder minimumShouldMatch(String msm) { this.minimumShouldMatch = msm; return this; }
	public L1esMultiMatchQueryBuilder fuzzyRewrite(String fuzzyRewrite) { this.fuzzyRewrite = fuzzyRewrite; return this; }
	public L1esMultiMatchQueryBuilder tieBreaker(Float tieBreaker) { this.tieBreaker = tieBreaker; return this; }
	public L1esMultiMatchQueryBuilder lenient(boolean lenient) { this.lenient = lenient; return this; }
	public L1esMultiMatchQueryBuilder zeroTermsQuery(ZeroTermsQueryOption ztq) { this.zeroTermsQuery = ztq; return this; }
	public L1esMultiMatchQueryBuilder autoGenerateSynonymsPhraseQuery(boolean v) { this.autoGenerateSynonymsPhraseQuery = v; return this; }
	public L1esMultiMatchQueryBuilder fuzzyTranspositions(boolean v) { this.fuzzyTranspositions = v; return this; }
	public L1esMultiMatchQueryBuilder cutoffFrequency(Float f) { /* removed in 8.x, no-op */ return this; }

	@Override
	public String getWriteableName() {
		return NAME;
	}

	@Override
	public void doXContent(XContentBuilder builder, Params params) throws IOException {
		builder.startObject(NAME);
		builder.field(QUERY_FIELD.getPreferredName(), value);

		builder.startArray(FIELDS_FIELD.getPreferredName());
		for (Map.Entry<String, Float> fieldEntry : fieldsBoosts.entrySet()) {
			builder.value(fieldEntry.getKey() + "^" + fieldEntry.getValue());
		}
		builder.endArray();

		builder.field(TYPE_FIELD.getPreferredName(), type.toString().toLowerCase(Locale.ENGLISH));
		builder.field(OPERATOR_FIELD.getPreferredName(), operator.toString());

		if (analyzer != null) {
			builder.field(ANALYZER_FIELD.getPreferredName(), analyzer);
		}

		builder.field(SLOP_FIELD.getPreferredName(), slop);

		if (fuzziness != null) {
			fuzziness.toXContent(builder, params);
		}

		builder.field(PREFIX_LENGTH_FIELD.getPreferredName(), prefixLength);
		builder.field(MAX_EXPANSIONS_FIELD.getPreferredName(), maxExpansions);

		if (minimumShouldMatch != null) {
			builder.field(MINIMUM_SHOULD_MATCH_FIELD.getPreferredName(), minimumShouldMatch);
		}

		if (fuzzyRewrite != null) {
			builder.field(FUZZY_REWRITE_FIELD.getPreferredName(), fuzzyRewrite);
		}

		if (tieBreaker != null) {
			builder.field(TIE_BREAKER_FIELD.getPreferredName(), tieBreaker);
		}

		if (lenient != null) {
			builder.field(LENIENT_FIELD.getPreferredName(), lenient);
		}

		builder.field(ZERO_TERMS_QUERY_FIELD.getPreferredName(), zeroTermsQuery.toString());
		builder.field(GENERATE_SYNONYMS_PHRASE_QUERY.getPreferredName(), autoGenerateSynonymsPhraseQuery);
		builder.field(FUZZY_TRANSPOSITIONS_FIELD.getPreferredName(), fuzzyTranspositions);

		printBoostAndQueryName(builder);
		builder.endObject();
	}

	@Override
	protected Query doToQuery(SearchExecutionContext context) throws IOException {
		if (!L1esConfig.get().flags.multiMatchQueryEnabled()) {
			return buildStandardMultiMatchQuery().toQuery(context);
		}

		if (client == null) {
			logger.warn("Missing client, falling back to default multi match query builder.");
			return buildStandardMultiMatchQuery().toQuery(context);
		}

		if (type == MultiMatchQueryBuilder.Type.CROSS_FIELDS) {
			logger.warn("L1es search doesn't support cross fields searching (yet), fall back to default (index - {}).",
					context.index().getName());
			return buildStandardMultiMatchQuery().toQuery(context);
		}

		L1esMultiMatchQuery multiMatchQuery = new L1esMultiMatchQuery(context, client, DmlDB.of(client));

		if (analyzer != null) {
			if (context.getIndexAnalyzers().get(analyzer) == null) {
				throw new QueryShardException(context, "[" + NAME + "] analyzer [" + analyzer + "] not found");
			}
			multiMatchQuery.setAnalyzer(analyzer);
		}

		multiMatchQuery.setPhraseSlop(slop);

		if (fuzziness != null) {
			multiMatchQuery.setFuzziness(fuzziness);
		}

		multiMatchQuery.setFuzzyPrefixLength(prefixLength);
		multiMatchQuery.setMaxExpansions(maxExpansions);
		multiMatchQuery.setOccur(operator.toBooleanClauseOccur());

		if (fuzzyRewrite != null) {
			multiMatchQuery.setFuzzyRewriteMethod(
					QueryParsers.parseRewriteMethod(fuzzyRewrite, null, LoggingDeprecationHandler.INSTANCE));
		}

		if (tieBreaker != null) {
			multiMatchQuery.setTieBreaker(tieBreaker);
		}

		if (lenient != null) {
			multiMatchQuery.setLenient(lenient);
		}

		multiMatchQuery.setZeroTermsQuery(zeroTermsQuery);
		multiMatchQuery.setAutoGenerateSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery);
		multiMatchQuery.setTranspositions(fuzzyTranspositions);

		Map<String, Float> newFieldsBoosts;
		boolean isAllField;

		if (fieldsBoosts.isEmpty()) {
			List<String> defaultFields = context.defaultFields();
			newFieldsBoosts = QueryParserHelper.resolveMappingFields(context,
					QueryParserHelper.parseFieldsAndWeights(defaultFields));
			isAllField = QueryParserHelper.hasAllFieldsWildcard(defaultFields);
		} else {
			newFieldsBoosts = QueryParserHelper.resolveMappingFields(context, fieldsBoosts);
			isAllField = QueryParserHelper.hasAllFieldsWildcard(fieldsBoosts.keySet());
		}

		if ((isAllField) && (lenient == null)) {
			multiMatchQuery.setLenient(true);
		}

		return multiMatchQuery.parse(type, newFieldsBoosts, value, minimumShouldMatch);
	}

	private MultiMatchQueryBuilder buildStandardMultiMatchQuery() {
		MultiMatchQueryBuilder mmqb = new MultiMatchQueryBuilder(value);
		mmqb.fields(fieldsBoosts);
		mmqb.type(type);
		mmqb.operator(operator);
		mmqb.analyzer(analyzer);
		mmqb.slop(slop);
		if (fuzziness != null) mmqb.fuzziness(fuzziness);
		mmqb.prefixLength(prefixLength);
		mmqb.maxExpansions(maxExpansions);
		mmqb.minimumShouldMatch(minimumShouldMatch);
		mmqb.fuzzyRewrite(fuzzyRewrite);
		mmqb.tieBreaker(tieBreaker);
		if (lenient != null) mmqb.lenient(lenient);
		mmqb.zeroTermsQuery(zeroTermsQuery);
		mmqb.autoGenerateSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery);
		mmqb.fuzzyTranspositions(fuzzyTranspositions);
		return mmqb;
	}

	@Override
	protected boolean doEquals(L1esMultiMatchQueryBuilder other) {
		return Objects.equals(value, other.value)
				&& Objects.equals(fieldsBoosts, other.fieldsBoosts)
				&& Objects.equals(type, other.type)
				&& Objects.equals(operator, other.operator)
				&& Objects.equals(analyzer, other.analyzer)
				&& slop == other.slop
				&& Objects.equals(fuzziness, other.fuzziness)
				&& prefixLength == other.prefixLength
				&& maxExpansions == other.maxExpansions
				&& Objects.equals(minimumShouldMatch, other.minimumShouldMatch)
				&& Objects.equals(fuzzyRewrite, other.fuzzyRewrite)
				&& Objects.equals(tieBreaker, other.tieBreaker)
				&& Objects.equals(lenient, other.lenient)
				&& Objects.equals(zeroTermsQuery, other.zeroTermsQuery)
				&& autoGenerateSynonymsPhraseQuery == other.autoGenerateSynonymsPhraseQuery
				&& fuzzyTranspositions == other.fuzzyTranspositions;
	}

	@Override
	protected int doHashCode() {
		return Objects.hash(value, fieldsBoosts, type, operator, analyzer, slop, fuzziness,
				prefixLength, maxExpansions, minimumShouldMatch, fuzzyRewrite, tieBreaker,
				lenient, zeroTermsQuery, autoGenerateSynonymsPhraseQuery, fuzzyTranspositions);
	}

	@Override
	public TransportVersion getMinimalSupportedVersion() {
		return TransportVersions.ZERO;
	}

	/**
	 * Creates a new {@link L1esMultiMatchQueryBuilder} from a standard
	 * {@link MultiMatchQueryBuilder}, copying all parameters.
	 */
	public static L1esMultiMatchQueryBuilder fromStandard(MultiMatchQueryBuilder mmqb, Client client) {
		L1esMultiMatchQueryBuilder l1es = new L1esMultiMatchQueryBuilder(mmqb.value(), client);

		l1es.fields(mmqb.fields());
		l1es.type(mmqb.type());
		l1es.operator(mmqb.operator());
		l1es.analyzer(mmqb.analyzer());
		l1es.slop(mmqb.slop());

		if (mmqb.fuzziness() != null) {
			l1es.fuzziness(mmqb.fuzziness());
		}

		l1es.prefixLength(mmqb.prefixLength());
		l1es.maxExpansions(mmqb.maxExpansions());
		l1es.minimumShouldMatch(mmqb.minimumShouldMatch());
		l1es.fuzzyRewrite(mmqb.fuzzyRewrite());

		if (mmqb.tieBreaker() != null) {
			l1es.tieBreaker(mmqb.tieBreaker());
		}

		l1es.lenient(mmqb.lenient());
		l1es.zeroTermsQuery(mmqb.zeroTermsQuery());
		l1es.autoGenerateSynonymsPhraseQuery(mmqb.autoGenerateSynonymsPhraseQuery());
		l1es.fuzzyTranspositions(mmqb.fuzzyTranspositions());
		l1es.queryName(mmqb.queryName());
		l1es.boost(mmqb.boost());

		return l1es;
	}

	public static L1esMultiMatchQueryBuilder fromXContent(XContentParser parser, Client client) throws IOException {
		Object value = null;
		Map<String, Float> fieldsBoosts = new HashMap<>();
		MultiMatchQueryBuilder.Type type = MultiMatchQueryBuilder.Type.BEST_FIELDS;
		String analyzer = null;
		int slop = 0;
		Fuzziness fuzziness = null;
		int prefixLength = 0;
		int maxExpansions = 50;
		Operator operator = Operator.OR;
		String minimumShouldMatch = null;
		String fuzzyRewrite = null;
		Float tieBreaker = null;
		Boolean lenient = null;
		ZeroTermsQueryOption zeroTermsQuery = ZeroTermsQueryOption.NONE;
		boolean autoGenerateSynonymsPhraseQuery = true;
		boolean fuzzyTranspositions = true;

		float boost = AbstractQueryBuilder.DEFAULT_BOOST;
		String queryName = null;

		XContentParser.Token token;
		String currentFieldName = null;
		while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
			if (token == XContentParser.Token.FIELD_NAME) {
				currentFieldName = parser.currentName();
			} else if (FIELDS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
				if (token == XContentParser.Token.START_ARRAY) {
					while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
						parseFieldAndBoost(parser, fieldsBoosts);
					}
				} else if (token.isValue()) {
					parseFieldAndBoost(parser, fieldsBoosts);
				} else {
					throw new XContentParseException(parser.getTokenLocation(),
							"[" + NAME + "] query does not support [" + currentFieldName + "]");
				}
			} else if (token.isValue()) {
				if (QUERY_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					value = parser.objectText();
				} else if (TYPE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					type = MultiMatchQueryBuilder.Type.parse(parser.text(), parser.getDeprecationHandler());
				} else if (ANALYZER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					analyzer = parser.text();
				} else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					boost = parser.floatValue();
				} else if (SLOP_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					slop = parser.intValue();
				} else if (Fuzziness.FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					fuzziness = Fuzziness.parse(parser);
				} else if (PREFIX_LENGTH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					prefixLength = parser.intValue();
				} else if (MAX_EXPANSIONS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					maxExpansions = parser.intValue();
				} else if (OPERATOR_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					operator = Operator.fromString(parser.text());
				} else if (MINIMUM_SHOULD_MATCH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					minimumShouldMatch = parser.textOrNull();
				} else if (FUZZY_REWRITE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					fuzzyRewrite = parser.textOrNull();
				} else if (TIE_BREAKER_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					tieBreaker = parser.floatValue();
				} else if (LENIENT_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					lenient = parser.booleanValue();
				} else if (ZERO_TERMS_QUERY_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					String zeroTermsValue = parser.text();
					if ("none".equalsIgnoreCase(zeroTermsValue)) {
						zeroTermsQuery = ZeroTermsQueryOption.NONE;
					} else if ("all".equalsIgnoreCase(zeroTermsValue)) {
						zeroTermsQuery = ZeroTermsQueryOption.ALL;
					} else {
						throw new XContentParseException(parser.getTokenLocation(),
								"Unsupported zero_terms_query value [" + zeroTermsValue + "]");
					}
				} else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					queryName = parser.text();
				} else if (GENERATE_SYNONYMS_PHRASE_QUERY.match(currentFieldName, parser.getDeprecationHandler())) {
					autoGenerateSynonymsPhraseQuery = parser.booleanValue();
				} else if (FUZZY_TRANSPOSITIONS_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					fuzzyTranspositions = parser.booleanValue();
				} else {
					throw new XContentParseException(parser.getTokenLocation(),
							"[" + NAME + "] query does not support [" + currentFieldName + "]");
				}
			} else {
				throw new XContentParseException(parser.getTokenLocation(),
						"[" + NAME + "] unknown token [" + token + "] after [" + currentFieldName + "]");
			}
		}

		if (value == null) {
			throw new XContentParseException(parser.getTokenLocation(), "No text specified for multi_match query");
		}

		L1esMultiMatchQueryBuilder builder = new L1esMultiMatchQueryBuilder(value, client);

		builder.fields(fieldsBoosts);
		builder.type(type);
		builder.analyzer(analyzer);
		builder.fuzziness(fuzziness);
		builder.fuzzyRewrite(fuzzyRewrite);
		builder.maxExpansions(maxExpansions);
		builder.minimumShouldMatch(minimumShouldMatch);
		builder.operator(operator);
		builder.prefixLength(prefixLength);
		builder.slop(slop);
		builder.tieBreaker(tieBreaker);
		builder.zeroTermsQuery(zeroTermsQuery);
		builder.autoGenerateSynonymsPhraseQuery(autoGenerateSynonymsPhraseQuery);
		builder.boost(boost);
		builder.queryName(queryName);
		builder.fuzzyTranspositions(fuzzyTranspositions);

		if (lenient != null) {
			builder.lenient(lenient);
		}

		return builder;
	}

	private static void parseFieldAndBoost(XContentParser parser, Map<String, Float> fieldsBoosts) throws IOException {
		String fField = null;
		Float fBoost = AbstractQueryBuilder.DEFAULT_BOOST;
		char[] fieldText = parser.textCharacters();
		int end = parser.textOffset() + parser.textLength();
		for (int i = parser.textOffset(); i < end; i++) {
			if (fieldText[i] == '^') {
				int relativeLocation = i - parser.textOffset();
				fField = new String(fieldText, parser.textOffset(), relativeLocation);
				fBoost = Float.parseFloat(new String(fieldText, i + 1, parser.textLength() - relativeLocation - 1));
				break;
			}
		}
		if (fField == null) {
			fField = parser.text();
		}
		fieldsBoosts.put(fField, fBoost);
	}

	public static class Factory extends L1esQuerySpecFactory<L1esMultiMatchQueryBuilder> {
		public static final Factory Instance = new Factory();

		@Override
		public String queryName() {
			return L1esMultiMatchQueryBuilder.NAME;
		}

		@Override
		protected L1esMultiMatchQueryBuilder newQueryBuilder(StreamInput in) throws IOException {
			return new L1esMultiMatchQueryBuilder(in, client);
		}

		@Override
		protected L1esMultiMatchQueryBuilder newQueryBuilder(XContentParser parser) throws IOException {
			return L1esMultiMatchQueryBuilder.fromXContent(parser, client);
		}
	}
}
