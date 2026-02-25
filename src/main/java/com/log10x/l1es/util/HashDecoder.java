package com.log10x.l1es.util;

import java.util.Arrays;

/**
 * Decodes base91-encoded template hash strings to their underlying long
 * representation. Inlined from FastJsonSafeBase to avoid a dependency
 * on l1x-inc/util.
 */
public class HashDecoder {

	private static final char[] BASE91_CHARS = (
			" !#$&*+./0123456789:;<>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_abcdefghijklmnopqrstuvwxyz{|}"
	).toCharArray();

	private static final int BASE = BASE91_CHARS.length;

	private static final int[] DECODING_TABLE = new int[128];

	static {
		Arrays.fill(DECODING_TABLE, -1);
		for (int i = 0; i < BASE91_CHARS.length; i++) {
			DECODING_TABLE[BASE91_CHARS[i]] = i;
		}
	}

	/**
	 * Decodes a base91-encoded hash string to its long value.
	 *
	 * @param hash the base91-encoded hash string
	 * @return the decoded long value
	 * @throws IllegalArgumentException if the string contains invalid characters
	 */
	public static long decode(String hash) {
		int length = hash.length();
		if (length == 0) {
			throw new IllegalArgumentException("Empty hash");
		}

		boolean negative = hash.charAt(0) == '-';
		int index = negative ? 1 : 0;
		long value = 0;

		while (index < length) {
			int digit = hash.charAt(index++);
			if (digit > 127 || DECODING_TABLE[digit] == -1) {
				throw new IllegalArgumentException(
						"Invalid character: " + (char) digit);
			}
			value = value * BASE + DECODING_TABLE[digit];
		}

		return negative ? -value : value;
	}
}
