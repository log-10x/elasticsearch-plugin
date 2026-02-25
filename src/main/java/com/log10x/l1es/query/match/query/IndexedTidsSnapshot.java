package com.log10x.l1es.query.match.query;

import java.util.Set;

/**
 * Immutable holder for indexed l1x_tid values, keyed by index name and
 * reader version. Allows detecting staleness when segments refresh or
 * when a different index is queried.
 */
final class IndexedTidsSnapshot {

	/** The ES index name this snapshot was built for. */
	final String indexName;

	/** The IndexReader version at the time this snapshot was built. */
	final long readerVersion;

	/** The set of l1x_tid values present in the index. */
	final Set<String> tids;

	IndexedTidsSnapshot(String indexName, long readerVersion, Set<String> tids) {
		this.indexName = indexName;
		this.readerVersion = readerVersion;
		this.tids = tids;
	}

	/**
	 * Checks whether this snapshot is still valid for the given index name
	 * and reader version.
	 */
	boolean isValid(String indexName, long readerVersion) {
		return this.indexName.equals(indexName) && this.readerVersion == readerVersion;
	}
}
