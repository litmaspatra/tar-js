package com.tarjs.app.core

import java.time.Instant
import java.time.ZoneId

fun needsDateSeparator(
    previousEpochSeconds: Long?,
    currentEpochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Boolean {
    if (currentEpochSeconds <= 0L) return false
    if (previousEpochSeconds == null || previousEpochSeconds <= 0L) return true
    val previousDay = Instant.ofEpochSecond(previousEpochSeconds).atZone(zoneId).toLocalDate()
    val currentDay = Instant.ofEpochSecond(currentEpochSeconds).atZone(zoneId).toLocalDate()
    return previousDay != currentDay
}

fun sampleSizeFor(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sampleSize = 1
    while (
        sourceWidth / (sampleSize * 2) >= targetWidth &&
        sourceHeight / (sampleSize * 2) >= targetHeight
    ) {
        sampleSize *= 2
    }
    return sampleSize
}
