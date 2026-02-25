package com.log10x.l1es.query.match.query;

import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Immutable holder for all DML-derived in-memory index structures.
 *
 * <p>All fields are built together inside a single synchronized block and
 * then published atomically via a single volatile write. Reader threads
 * always see a consistent set of structures — no partial updates.
 */
final class DmlIndexSnapshot {

	/** Inverted index: token → set of template hash longs containing that token. */
	final Map<String, LongOpenHashSet> tokenIndex;

	/** Per-template static token sets (excludes VARIABLE_TOKEN). Keyed by decoded hash long. */
	final Long2ObjectOpenHashMap<Set<String>> staticTokens;

	/** Set of decoded hash longs for templates that contain variables. */
	final LongOpenHashSet hasVariables;

	/** Reverse map: decoded hash long → original hash string (for TermInSetQuery BytesRef). */
	final Long2ObjectOpenHashMap<String> hashLongToString;

	/** Bigram index: "token1\0token2" → set of template hash longs containing that bigram. */
	final Map<String, LongOpenHashSet> bigramIndex;

	/** Timestamp (System.nanoTime) when this snapshot was built. Used for TTL-based invalidation. */
	final long builtAtNanos;

	DmlIndexSnapshot(Map<String, LongOpenHashSet> tokenIndex,
			Long2ObjectOpenHashMap<Set<String>> staticTokens,
			LongOpenHashSet hasVariables,
			Long2ObjectOpenHashMap<String> hashLongToString,
			Map<String, LongOpenHashSet> bigramIndex) {
		this.tokenIndex = tokenIndex;
		this.staticTokens = staticTokens;
		this.hasVariables = hasVariables;
		this.hashLongToString = hashLongToString;
		this.bigramIndex = bigramIndex;
		this.builtAtNanos = System.nanoTime();
	}
}
