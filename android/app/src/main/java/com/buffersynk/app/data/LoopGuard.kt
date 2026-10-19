package com.buffersynk.app.data

/** Loop-prevention hashes shared between SyncService and UI/clipboard trampolines (same process). */
object LoopGuard {
    /** Hash of content we just applied locally from the PC — never echo it back. */
    @Volatile
    var lastAppliedHash: String? = null

    /** Hash of content we just pushed to the PC — never re-send it. */
    @Volatile
    var lastSentHash: String? = null
}
