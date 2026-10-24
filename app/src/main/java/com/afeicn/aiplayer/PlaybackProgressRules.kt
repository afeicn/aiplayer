package com.afeicn.aiplayer

internal object PlaybackProgressRules {
    fun resumePosition(sameMedia: Boolean, livePosition: Long, savedPosition: Long): Long =
        (if (sameMedia) livePosition else savedPosition).coerceAtLeast(0L)

    fun saveOutgoing(oldId: String?, newId: String?): Boolean = oldId != null && oldId != newId
}
