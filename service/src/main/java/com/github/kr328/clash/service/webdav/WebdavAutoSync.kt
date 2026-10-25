package com.github.kr328.clash.service.webdav

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

object WebdavAutoSync {
    private val gate = Any()
    private var generation = 0
    private var failures = 0
    private var dirty = false

    fun enqueue(context: Context) {
        schedule(context.applicationContext, WEBDAV_PUSH_DEBOUNCE_MS, resetFailures = true)
    }

    fun syncIfDue(context: Context) {
        val app = context.applicationContext
        Global.launch(Dispatchers.IO) {
            runCatching { pullIfDue(app) }
                .onFailure { Log.w("WebDAV pull: ${it.message}") }
        }
    }

    fun noteDeleted(context: Context, uuid: UUID, source: String, type: String, updatedAt: Long) {
        val deletedAt = maxOf(System.currentTimeMillis(), updatedAt + 1)
        WebdavLocalState(context).update { state ->
            val id = state.clientId.ifBlank { createWebdavClientId() }
            val key = uuid.toString()
            state.copy(
                clientId = id,
                tombstones = state.tombstones + TombstonePayload(
                    uuid = key,
                    source = source,
                    type = type,
                    deletedAt = deletedAt,
                    rev = state.profileSyncRev[key] ?: 0L,
                    writerId = id,
                ),
            )
        }
    }

    fun noteAccessControl(context: Context) {
        val now = System.currentTimeMillis()
        WebdavLocalState(context).update { state ->
            val id = state.clientId.ifBlank { createWebdavClientId() }
            state.copy(
                clientId = id,
                accessControlUpdatedAt = now,
                accessSyncRev = state.accessSyncRev + 1,
                accessWriterId = id,
            )
        }
    }

    fun noteAppsStrategy(context: Context, uuid: UUID) {
        val now = System.currentTimeMillis()
        WebdavLocalState(context).update { state ->
            val id = state.clientId.ifBlank { createWebdavClientId() }
            val key = uuid.toString()
            state.copy(
                clientId = id,
                strategyUpdatedAt = state.strategyUpdatedAt + (key to now),
                strategySyncRev = state.strategySyncRev + (key to ((state.strategySyncRev[key] ?: 0L) + 1)),
                strategyWriterId = state.strategyWriterId + (key to id),
            )
        }
    }

    fun noteActiveAppsStrategy(context: Context) {
        val now = System.currentTimeMillis()
        WebdavLocalState(context).update { state ->
            val id = state.clientId.ifBlank { createWebdavClientId() }
            state.copy(
                clientId = id,
                activeStrategyUpdatedAt = now,
                activeStrategySyncRev = state.activeStrategySyncRev + 1,
                activeStrategyWriterId = id,
            )
        }
    }

    fun noteStrategyDeleted(context: Context, uuid: UUID) {
        val deletedAt = System.currentTimeMillis()
        WebdavLocalState(context).update { state ->
            val id = state.clientId.ifBlank { createWebdavClientId() }
            val key = uuid.toString()
            state.copy(
                clientId = id,
                strategyTombstones = state.strategyTombstones + TombstonePayload(
                    uuid = key,
                    type = "AppsStrategy",
                    deletedAt = deletedAt,
                    rev = state.strategySyncRev[key] ?: 0L,
                    writerId = id,
                ),
                strategyUpdatedAt = state.strategyUpdatedAt - key,
                strategySyncRev = state.strategySyncRev - key,
                strategyWriterId = state.strategyWriterId - key,
            )
        }
    }

    private fun schedule(app: Context, waitMs: Long, resetFailures: Boolean) {
        val token = synchronized(gate) {
            if (resetFailures) failures = 0
            dirty = true
            ++generation
        }
        Global.launch(Dispatchers.IO) {
            delay(waitMs)
            val run = synchronized(gate) {
                if (token != generation) false else {
                    dirty = false
                    true
                }
            }
            if (!run) return@launch
            try {
                push(app)
                synchronized(gate) { failures = 0 }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                Log.w("WebDAV sync: ${err.message}")
                val wait = synchronized(gate) {
                    failures += 1
                    dirty = true
                    if (failures <= 3) WEBDAV_PUSH_DEBOUNCE_MS else WEBDAV_PULL_INTERVAL_MS
                }
                schedule(app, wait, resetFailures = false)
            }
        }
    }

    private suspend fun pullIfDue(context: Context) {
        val settings = WebdavSettingsStore(context).load()
        if (!ready(settings)) return
        val last = WebdavLocalState(context).read().lastPullAt
        if (System.currentTimeMillis() - last < WEBDAV_PULL_INTERVAL_MS) return
        WebdavSync(context).perform(WebdavSync.ACTION_PULL, settings)
    }

    private suspend fun push(context: Context) {
        val settings = WebdavSettingsStore(context).load()
        if (!ready(settings)) return
        WebdavSync(context).perform(WebdavSync.ACTION_SYNC, settings)
    }

    private fun ready(settings: StoredWebdavSettings): Boolean {
        return settings.autoSync && settings.hasEndpoint() && settings.username.isNotBlank() && settings.password.isNotEmpty()
    }
}
