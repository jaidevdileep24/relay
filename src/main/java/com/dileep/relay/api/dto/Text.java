package com.dileep.relay.api.dto;

/**
 * Shared string rules for request DTOs.
 *
 * <p>Postgres TEXT and JSONB cannot store U+0000. It is valid JSON, so it gets
 * through Jackson and only fails at INSERT - as a 500. Rejecting it here makes
 * it the 400 it should be.
 */
public final class Text {

	public static final String NO_NUL = "[^\\x00]*";
	public static final String NO_NUL_MESSAGE = "must not contain the NUL character";

	private Text() {
	}
}
