package com.log10x.l1es.query.factoy;

import java.io.IOException;

import org.apache.lucene.search.Query;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParser;
import org.elasticsearch.plugins.SearchPlugin.QuerySpec;

/**
 * Factory class for generating {@link QuerySpec} of the L1es queries.
 * 
 * For info as to why this class is needed, see {@link #createSpec}
 */
public abstract class L1esQuerySpecFactory<T extends QueryBuilder> {

	/**
	 * The {@link Client} to use for operations against the ES cluster.
	 */
	protected volatile Client client;

	/**
	 * Sets the {@link Client} to use for operations against the ES cluster.
	 * 
	 * @param client the {@link Client} to use.
	 */
	public void setClient(Client client) {
		this.client = client;
	}

	/**
	 * @return the name of the {@link Query} to be built this factory's
	 *         {@link QuerySpec}
	 */
	public abstract String queryName();

	/**
	 * @param in a {@link StreamInput} to read the query data from.
	 * @return a new {@link QueryBuilder} from the provided {@link StreamInput}
	 * 
	 * @throws IOException in case of internal parsing errors.
	 */
	protected abstract T newQueryBuilder(StreamInput in) throws IOException;

	/**
	 * @param parser a {@link XContentParser} to read the query data from.
	 * @return a new {@link QueryBuilder} from the provided {@link XContentParser}
	 * 
	 * @throws IOException in case of internal parsing errors.
	 */
	protected abstract T newQueryBuilder(XContentParser parser) throws IOException;

	/**
	 * Creates a {@link QuerySpec} wrapper for the factories matching
	 * {@link QueryBuilder}.
	 * 
	 * The reason we need this is to wrap calls to newQueryBuilder, as we want to
	 * propagate the {@link Client}. Otherwise we could have simply provided stuff
	 * like L1esQueryBuilder::new...
	 * 
	 * Also note we can't explicitly use {@link #client} here, as it might not be
	 * ready yet. That's why we delegate the responsibility of propagating the
	 * client to the abstract {@link #newQueryBuilder} methods.
	 * 
	 * @return the new {@link QuerySpec} specified by this factory.
	 */
	public QuerySpec<T> createSpec() {
		return new QuerySpec<T>(
				queryName(),
				new Writeable.Reader<T>() {
					@Override
					public T read(StreamInput in) throws IOException {
						return newQueryBuilder(in);
					}
				},
				new QueryParser<T>() {
					@Override
					public T fromXContent(XContentParser parser) throws IOException {
						return newQueryBuilder(parser);
					}
				});
	}
}
