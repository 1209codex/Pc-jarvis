package com.jarvis.tools

import com.jarvis.execution.VerificationResult

/**
 * A lazy-loading wrapper for [Tool] instances that defers the instantiation of the
 * underlying tool implementation until its first invocation via [execute].
 *
 * This allows [ToolRegistry] and planning engines to discover tool metadata, names,
 * and policies during cold start without allocating expensive hardware resources
 * (camera streams, neural detectors, SQLite databases, or accessibility engines).
 */
class LazyTool(
    override val name: String,
    override val description: String = "",
    override val policy: ToolPolicy = ToolPolicy(),
    private val toolProvider: () -> Tool
) : Tool {

    @Volatile
    private var delegate: Tool? = null

    /** Returns true if the underlying tool delegate has already been instantiated. */
    val isInitialized: Boolean
        get() = delegate != null

    /**
     * Resolves and returns the underlying [Tool], initializing it thread-safely if needed.
     */
    fun getOrInit(): Tool {
        return delegate ?: synchronized(this) {
            delegate ?: toolProvider().also { delegate = it }
        }
    }

    /**
     * Evicts the cached delegate so that its memory can be reclaimed under memory pressure.
     * The tool will be transparently re-instantiated on next execution.
     */
    fun evict() {
        synchronized(this) {
            delegate = null
        }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return getOrInit().execute(params)
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        return delegate?.verify(params, result)
            ?: VerificationResult.unknown("No specialized verifier for '$name'")
    }
}
