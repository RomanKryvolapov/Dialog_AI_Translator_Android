package com.romankryvolapov.dialogaitranslator.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Background work. Supervised, with a logging handler: a coroutine that fails with nothing to catch it ends alone and
 * is logged, instead of cancelling every other piece of work in the scope.
 */
val backgroundThreadScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        LoggerFactory.getLogger("Coroutines").error("A background task failed: {}", error.message, error)
    },
)
