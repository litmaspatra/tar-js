package com.tarjs.app.core

/** Prevents filesystem paths, provider details, and library internals reaching the UI. */
fun Throwable.userFacingMessage(fallback: String): String = fallback
