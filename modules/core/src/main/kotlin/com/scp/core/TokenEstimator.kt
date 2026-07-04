package com.scp.core

import kotlin.math.ceil

/** Swappable token counting — the budget algorithm doesn't care how tokens are counted. */
public fun interface TokenEstimator {
    public fun estimate(text: String): Int
}

/** The standard ~4-chars-per-token heuristic (docs/05 §4). */
public object CharsPerTokenEstimator : TokenEstimator {
    private const val CHARS_PER_TOKEN = 4.0

    override fun estimate(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()
}
