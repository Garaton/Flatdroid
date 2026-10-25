package com.garathon.flatdroid.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** For process-wide state holders (setup, session and task state), which live as long as the process. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
