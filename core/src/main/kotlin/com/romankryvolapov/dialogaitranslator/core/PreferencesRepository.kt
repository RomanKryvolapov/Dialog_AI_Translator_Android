package com.romankryvolapov.dialogaitranslator.core

import kotlinx.coroutines.flow.StateFlow

interface PreferencesRepository {

    val appInfoFlow: StateFlow<ApplicationInfo>
    fun getAppInfo(): ApplicationInfo
    fun update(change: (ApplicationInfo) -> ApplicationInfo)
}
