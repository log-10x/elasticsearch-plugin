package com.log10x.l1es.util;

/**
 * Utility class for general L1es consts.
 */
public class L1esConsts {

	// TODO - should be configurable.
	//
	/**
	 * Default value representing variables in L1x encoded data.
	 */
	public static final String VARIABLE_TOKEN = "$";

	/**
	 * Name of the optional keyword field containing the template ID.
	 * When present in the index mapping, enables exact keyword matching
	 * for dramatically faster query performance.
	 */
	public static final String L1X_TID_FIELD = "l1x_tid";
}
