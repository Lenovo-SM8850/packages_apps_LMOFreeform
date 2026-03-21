// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.sidebar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner for clipboard publication, retention and orphan collection in this process. */
internal object ClipboardPersistence {
    private val mutex = Mutex()

    suspend fun <T> withOwnership(block: suspend () -> T): T = mutex.withLock { block() }
}
