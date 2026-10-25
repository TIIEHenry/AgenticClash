package com.github.kr328.clash.service.webdav

import android.content.Context
import android.util.Base64
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.ProfileReceiver
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.AppsStrategyConfig
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.legacyAccessControlNeedsMigration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.io.File
import java.util.UUID

data class LocalSnapshot(
    val profiles: List<ProfilePayload>,
    val access: AccessControlPayload,
    val tombstones: List<TombstonePayload>,
    val strategies: List<AppsStrategyPayload> = emptyList(),
    val strategyTombstones: List<TombstonePayload> = emptyList(),
    val activeAppsStrategy: String? = null,
    val activeAppsStrategyUpdatedAt: Long = 0,
    val activeAppsStrategySyncRev: Long = 0,
    val activeAppsStrategyWriterId: String = "",
    val profileUsage: List<ProfileUsagePayload> = emptyList(),
)

class WebdavProfiles(private val context: Context) {
    private val state = WebdavLocalState(context)

    suspend fun exportLocal(): LocalSnapshot {
        val local = state.read()
        val clientId = local.clientId.ifBlank { createWebdavClientId() }
        val usageByProfile = linkedMapOf<String, String>()
        val usageAtByProfile = linkedMapOf<String, Long>()
        val usageWriterByProfile = linkedMapOf<String, String>()
        val fingerprintByProfile = linkedMapOf<String, String>()
        val contentByProfile = linkedMapOf<String, String>()
        val updatedAtByProfile = linkedMapOf<String, Long>()
        val revByProfile = linkedMapOf<String, Long>()
        val writerByProfile = linkedMapOf<String, String>()
        val usagePayloads = mutableListOf<ProfileUsagePayload>()
        val profiles = ImportedDao().queryAllUUIDs().mapNotNull { uuid ->
            if (PendingDao().exists(uuid)) return@mapNotNull null
            val imported = ImportedDao().queryByUUID(uuid) ?: return@mapNotNull null
            val id = uuid.toString()
            val files = readFiles(context.importedDir.resolve(id))
            val fileFp = fingerprint(files)
            val storedFileFp = local.fingerprint[id]
            val storedContent = local.contentHash[id]
            val storedUpdatedAt = local.configUpdatedAt[id] ?: 0L
            val storedRev = local.profileSyncRev[id] ?: 0L
            val storedWriter = local.profileWriterId[id].orEmpty()
            val usage = usageStamp(imported.upload, imported.download, imported.total, imported.expire)
            val storedUsage = local.profileUsage[id]
            val draft = ProfilePayload(
                uuid = id,
                name = imported.name,
                type = imported.type.name,
                source = imported.source,
                interval = imported.interval,
                ageSecretKey = imported.ageSecretKey,
                files = files,
            )
            val content = profileContentHash(draft)
            val changed = storedContent != null && storedContent != content
            val unchanged = storedUpdatedAt > 0 && (
                storedContent == content ||
                    (storedContent == null && storedFileFp != null && storedFileFp == fileFp)
                )
            val fileUpdatedAt = files.maxOfOrNull { fileUpdatedAt(context, uuid, it.path) } ?: imported.createdAt
            val fileAt = fileUpdatedAt.coerceAtLeast(1)
            val updatedAt = if (unchanged) storedUpdatedAt else maxOf(fileAt, storedUpdatedAt) + 1
            val rev = if (changed) storedRev + 1 else storedRev
            val writer = if (changed) clientId else storedWriter
            val storedUsageAt = local.profileUsageUpdatedAt[id] ?: 0L
            val usageUnchanged = storedUsage == usage && storedUsageAt > 0
            val usageAt = when {
                usageUnchanged -> storedUsageAt
                storedUsage == null -> maxOf(updatedAt, 1L)
                else -> maxOf(System.currentTimeMillis(), storedUsageAt + 1)
            }
            val usageWriter = when {
                usageUnchanged -> local.profileUsageWriter[id].orEmpty()
                storedUsage == null -> ""
                else -> clientId
            }
            if (storedUsage != usage) usageByProfile[id] = usage
            if (storedUsageAt != usageAt) usageAtByProfile[id] = usageAt
            if (local.profileUsageWriter[id].orEmpty() != usageWriter) usageWriterByProfile[id] = usageWriter
            if (storedFileFp != fileFp) fingerprintByProfile[id] = fileFp
            if (storedContent != content) contentByProfile[id] = content
            if (updatedAt > storedUpdatedAt) updatedAtByProfile[id] = updatedAt
            if (rev != storedRev) revByProfile[id] = rev
            if (writer != storedWriter) writerByProfile[id] = writer
            usagePayloads.add(
                ProfileUsagePayload(
                    uuid = id,
                    upload = imported.upload,
                    download = imported.download,
                    total = imported.total,
                    expire = imported.expire,
                    updatedAt = usageAt,
                    writerId = usageWriter,
                ),
            )
            draft.copy(
                upload = imported.upload,
                download = imported.download,
                total = imported.total,
                expire = imported.expire,
                createdAt = imported.createdAt,
                updatedAt = updatedAt,
                syncRev = rev,
                writerId = writer,
            )
        }
        if (
            usageByProfile.isNotEmpty() ||
            usageAtByProfile.isNotEmpty() ||
            usageWriterByProfile.isNotEmpty() ||
            fingerprintByProfile.isNotEmpty() ||
            contentByProfile.isNotEmpty() ||
            updatedAtByProfile.isNotEmpty() ||
            revByProfile.isNotEmpty() ||
            writerByProfile.isNotEmpty() ||
            local.clientId.isBlank()
        ) {
            state.update { current ->
                val nextUpdated = current.configUpdatedAt.toMutableMap()
                for ((id, stamp) in updatedAtByProfile) {
                    if (stamp > (nextUpdated[id] ?: 0L)) nextUpdated[id] = stamp
                }
                current.copy(
                    clientId = current.clientId.ifBlank { clientId },
                    configUpdatedAt = nextUpdated,
                    fingerprint = current.fingerprint + fingerprintByProfile,
                    contentHash = current.contentHash + contentByProfile,
                    profileSyncRev = current.profileSyncRev + revByProfile,
                    profileWriterId = current.profileWriterId + writerByProfile,
                    profileUsage = current.profileUsage + usageByProfile,
                    profileUsageUpdatedAt = current.profileUsageUpdatedAt + usageAtByProfile,
                    profileUsageWriter = current.profileUsageWriter + usageWriterByProfile,
                )
            }
        }
        val store = ServiceStore(context)
        val legacyAccess = AccessControlPayload(
            updatedAt = local.accessControlUpdatedAt,
            syncRev = local.accessSyncRev,
            writerId = local.accessWriterId,
        )
        val strategies = exportStrategies(store, local)
        val active = exportActiveStrategy(store, local)
        return LocalSnapshot(
            profiles = profiles,
            access = legacyAccess,
            tombstones = local.tombstones,
            strategies = strategies,
            strategyTombstones = local.strategyTombstones,
            activeAppsStrategy = active.first,
            activeAppsStrategyUpdatedAt = active.second,
            activeAppsStrategySyncRev = local.activeStrategySyncRev,
            activeAppsStrategyWriterId = local.activeStrategyWriterId,
            profileUsage = usagePayloads.sortedBy { it.uuid },
        )
    }

    suspend fun apply(
        result: MergeResult,
        local: LocalSnapshot,
        startedAt: Long,
        onProgress: (current: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): ApplyCounts {
        var downloaded = 0
        var removed = 0
        var skipped = 0
        val localByUuid = local.profiles.associateBy { it.uuid }
        val mergedIds = result.profiles.map { it.uuid }.toSet()
        val total = result.profiles.size

        for ((index, profile) in result.profiles.withIndex()) {
            onProgress(index + 1, total, profile.name)
            val previous = localByUuid[profile.uuid]
                ?: local.profiles.find { sameSubscription(it, profile) }
            val usage = result.profileUsage.find { it.uuid == profile.uuid }
            if (previous != null && profileBodyFingerprint(previous) == profileBodyFingerprint(profile)) {
                if (!sameProfileUsage(previous, profile)) patchUsage(profile, usage)
                continue
            }
            val installed = install(profile, usage)
            if (!installed) {
                skipped += 1
            } else if (previous == null || profile.updatedAt > previous.updatedAt) {
                downloaded += 1
            }
        }

        for (profile in local.profiles) {
            if (profile.uuid in mergedIds) continue
            if (local.profiles.any { it.uuid != profile.uuid && sameSubscription(it, profile) && it.uuid in mergedIds }) {
                continue
            }
            val stillThere = result.profiles.any { sameSubscription(it, profile) }
            if (stillThere) continue
            if (PendingDao().exists(UUID.fromString(profile.uuid))) {
                skipped += 1
                continue
            }
            remove(UUID.fromString(profile.uuid))
            removed += 1
        }

        applyLegacyAccess(result.access)
        val strategyCounts = applyStrategies(result, local, startedAt)
        return ApplyCounts(
            downloaded = downloaded + strategyCounts.first,
            removed = removed + strategyCounts.second,
            skipped = skipped,
        )
    }

    private suspend fun patchUsage(payload: ProfilePayload, usage: ProfileUsagePayload?) {
        val uuid = UUID.fromString(payload.uuid)
        val row = ImportedDao().queryByUUID(uuid)
        if (
            row != null &&
            (row.upload != payload.upload || row.download != payload.download || row.total != payload.total || row.expire != payload.expire)
        ) {
            ImportedDao().update(
                row.copy(
                    upload = payload.upload,
                    download = payload.download,
                    total = payload.total,
                    expire = payload.expire,
                ),
            )
            context.sendProfileChanged(uuid)
        }
        val stamp = usageStamp(payload.upload, payload.download, payload.total, payload.expire)
        val at = usage?.updatedAt ?: payload.updatedAt
        val writer = usage?.writerId ?: payload.writerId
        state.update { current ->
            current.copy(
                profileUsage = current.profileUsage + (payload.uuid to stamp),
                profileUsageUpdatedAt = current.profileUsageUpdatedAt + (payload.uuid to at),
                profileUsageWriter = current.profileUsageWriter + (payload.uuid to writer),
            )
        }
    }

    private suspend fun install(payload: ProfilePayload, usage: ProfileUsagePayload?): Boolean {
        val uuid = UUID.fromString(payload.uuid)
        val type = runCatching { Profile.Type.valueOf(payload.type) }.getOrElse { Profile.Type.File }
        return ProfileProcessor.guardImported {
            if (PendingDao().exists(uuid)) return@guardImported false
            val dir = context.importedDir.resolve(uuid.toString())
            val staging = context.importedDir.resolve("$uuid.webdav-tmp")
            staging.deleteRecursively()
            staging.mkdirs()
            var total = 0
            for (file in payload.files) {
                val rel = safeRelativePath(file.path)
                if (!isSyncedProfileFile(rel)) continue
                val bytes = Base64.decode(file.contentBase64, Base64.DEFAULT)
                if (bytes.size > WEBDAV_MAX_FILE_BYTES || total + bytes.size > WEBDAV_MAX_PROFILE_BYTES) {
                    throw WebdavException("too_large", detail = payload.name)
                }
                total += bytes.size
                val dest = staging.resolve(rel)
                dest.parentFile?.mkdirs()
                val rootPath = staging.canonicalFile.path + File.separator
                if (!dest.canonicalFile.path.startsWith(rootPath)) {
                    throw WebdavException("bad_path")
                }
                dest.writeBytes(bytes)
            }
            if (!staging.resolve("config.yaml").exists()) {
                staging.resolve("config.yaml").writeText("")
            }
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                staging.copyRecursively(dir, overwrite = true)
                staging.deleteRecursively()
            }
            val createdAt = payload.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            val row = Imported(
                uuid = uuid,
                name = payload.name.ifBlank { "Profile" },
                type = type,
                source = payload.source,
                interval = payload.interval,
                upload = payload.upload,
                download = payload.download,
                total = payload.total,
                expire = payload.expire,
                createdAt = createdAt,
                ageSecretKey = payload.ageSecretKey,
            )
            if (ImportedDao().exists(uuid)) ImportedDao().update(row) else ImportedDao().insert(row)
            context.sendProfileChanged(uuid)
            true
        }.also { installed ->
            if (!installed) return@also
            val id = uuid.toString()
            val stamp = usageStamp(payload.upload, payload.download, payload.total, payload.expire)
            state.update { current ->
                current.copy(
                    configUpdatedAt = current.configUpdatedAt + (id to payload.updatedAt),
                    fingerprint = current.fingerprint + (id to fingerprint(payload.files)),
                    contentHash = current.contentHash + (id to profileContentHash(payload)),
                    profileSyncRev = current.profileSyncRev + (id to payload.syncRev),
                    profileWriterId = current.profileWriterId + (id to payload.writerId),
                    profileUsage = current.profileUsage + (id to stamp),
                    profileUsageUpdatedAt = current.profileUsageUpdatedAt + (id to (usage?.updatedAt ?: payload.updatedAt)),
                    profileUsageWriter = current.profileUsageWriter + (id to (usage?.writerId ?: payload.writerId)),
                )
            }
            if (type != Profile.Type.File) {
                ImportedDao().queryByUUID(uuid)?.let { ProfileReceiver.scheduleNext(context, it) }
            }
        }
    }

    private suspend fun remove(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.let { ProfileReceiver.cancelNext(context, it) }
        ProfileProcessor.delete(context, uuid)
        val id = uuid.toString()
        state.update { current ->
            current.copy(
                configUpdatedAt = current.configUpdatedAt - id,
                fingerprint = current.fingerprint - id,
                contentHash = current.contentHash - id,
                profileSyncRev = current.profileSyncRev - id,
                profileWriterId = current.profileWriterId - id,
                profileUsage = current.profileUsage - id,
                profileUsageUpdatedAt = current.profileUsageUpdatedAt - id,
                profileUsageWriter = current.profileUsageWriter - id,
            )
        }
    }

    private suspend fun applyLegacyAccess(remote: AccessControlPayload) {
        val store = ServiceStore(context)
        if (store.legacyAccessControlMigrated) return
        if (remote.mode == AccessControlMode.AcceptAll.name && remote.packages.isEmpty()) {
            store.legacyAccessControlMigrated = true
            return
        }
        val mode = AccessControlMode.values().find { it.name == remote.mode } ?: return
        if (!legacyAccessControlNeedsMigration(mode, remote.packages)) return
        val active = store.activeAppsStrategyConfigUuid
            ?.let { id -> store.appsStrategyConfigs.find { it.uuid == id } }
        if (active != null) {
            store.legacyAccessControlMigrated = true
            return
        }
        val existing = store.appsStrategyConfigs.find {
            it.mode.name == remote.mode && it.packages.sorted() == remote.packages.sorted()
        }
        if (existing != null) {
            store.activeAppsStrategyConfigUuid = existing.uuid
            store.legacyAccessControlMigrated = true
            WebdavAutoSync.noteActiveAppsStrategy(context)
            return
        }
        val migrated = AppsStrategyConfig.create(
            name = context.getString(R.string.legacy_access_control_name),
            mode = mode,
            packages = remote.packages,
        )
        store.appsStrategyConfigs = store.appsStrategyConfigs + migrated
        store.activeAppsStrategyConfigUuid = migrated.uuid
        store.legacyAccessControlMigrated = true
        WebdavAutoSync.noteAppsStrategy(context, migrated.uuid)
        WebdavAutoSync.noteActiveAppsStrategy(context)
    }

    private fun exportStrategies(store: ServiceStore, local: LocalState): List<AppsStrategyPayload> {
        val configs = try {
            store.appsStrategyConfigs
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
        return configs.map { config ->
            val id = config.uuid.toString()
            var stamp = local.strategyUpdatedAt[id] ?: 0L
            if (stamp == 0L) {
                val stamped = System.currentTimeMillis()
                state.update { current ->
                    if ((current.strategyUpdatedAt[id] ?: 0L) > 0) current
                    else current.copy(strategyUpdatedAt = current.strategyUpdatedAt + (id to stamped))
                }
                stamp = state.read().strategyUpdatedAt[id] ?: stamped
            }
            AppsStrategyPayload(
                uuid = id,
                name = config.name,
                mode = config.mode.name,
                packages = config.packages.sorted(),
                updatedAt = stamp,
                syncRev = local.strategySyncRev[id] ?: 0L,
                writerId = local.strategyWriterId[id].orEmpty(),
            )
        }
    }

    private fun exportActiveStrategy(store: ServiceStore, local: LocalState): Pair<String?, Long> {
        val active = store.activeAppsStrategyConfigUuid?.toString()
        var stamp = local.activeStrategyUpdatedAt
        if (stamp == 0L && active != null) {
            val stamped = System.currentTimeMillis()
            state.update { current ->
                if (current.activeStrategyUpdatedAt > 0) current
                else current.copy(activeStrategyUpdatedAt = stamped)
            }
            stamp = state.read().activeStrategyUpdatedAt
        }
        return active to stamp
    }

    private fun applyStrategies(result: MergeResult, local: LocalSnapshot, startedAt: Long): Pair<Int, Int> {
        val store = ServiceStore(context)
        val fresh = state.read()
        val current = try {
            store.appsStrategyConfigs.associateBy { it.uuid.toString() }.toMutableMap()
        } catch (_: Exception) {
            return 0 to 0
        }
        var downloaded = 0
        var removed = 0
        val keep = result.strategies.map { it.uuid }.toSet()
        for (remote in result.strategies) {
            val previous = local.strategies.find { it.uuid == remote.uuid }
            val freshRev = fresh.strategySyncRev[remote.uuid] ?: 0L
            val freshAt = fresh.strategyUpdatedAt[remote.uuid] ?: 0L
            val freshWriter = fresh.strategyWriterId[remote.uuid].orEmpty()
            val localRank = if (previous == null) {
                SyncRank(freshRev, freshAt, freshWriter, "")
            } else {
                SyncRank(
                    maxOf(previous.syncRev, freshRev),
                    maxOf(previous.updatedAt, freshAt),
                    if (freshRev > previous.syncRev || (freshRev == previous.syncRev && freshAt > previous.updatedAt)) {
                        freshWriter
                    } else {
                        previous.writerId
                    },
                    strategyContentHash(previous),
                )
            }
            if (syncRankCompare(strategyRank(remote), localRank) <= 0 && (previous != null || current.containsKey(remote.uuid))) {
                continue
            }
            val mode = AccessControlMode.values().find { it.name == remote.mode } ?: AccessControlMode.AcceptAll
            current[remote.uuid] = AppsStrategyConfig(
                uuid = UUID.fromString(remote.uuid),
                name = remote.name.ifBlank { remote.uuid },
                mode = mode,
                packages = remote.packages,
            )
            state.update { item ->
                item.copy(
                    strategyUpdatedAt = item.strategyUpdatedAt + (remote.uuid to remote.updatedAt),
                    strategySyncRev = item.strategySyncRev + (remote.uuid to remote.syncRev),
                    strategyWriterId = item.strategyWriterId + (remote.uuid to remote.writerId),
                )
            }
            if (previous == null || syncRankCompare(strategyRank(remote), strategyRank(previous)) > 0) downloaded += 1
        }
        for (id in current.keys.toList()) {
            if (id in keep) continue
            val stamp = fresh.strategyUpdatedAt[id] ?: 0L
            val rev = fresh.strategySyncRev[id] ?: 0L
            if (stamp > startedAt || rev > (local.strategies.find { it.uuid == id }?.syncRev ?: 0L)) continue
            current.remove(id)
            removed += 1
            state.update { item ->
                item.copy(
                    strategyUpdatedAt = item.strategyUpdatedAt - id,
                    strategySyncRev = item.strategySyncRev - id,
                    strategyWriterId = item.strategyWriterId - id,
                )
            }
        }
        store.appsStrategyConfigs = current.values.toList()
        applyActiveStrategy(store, result)
        return downloaded to removed
    }

    private fun applyActiveStrategy(store: ServiceStore, result: MergeResult) {
        val fresh = state.read()
        val currentId = store.activeAppsStrategyConfigUuid?.toString().orEmpty()
        val remoteRank = SyncRank(
            result.activeAppsStrategySyncRev,
            result.activeAppsStrategyUpdatedAt,
            result.activeAppsStrategyWriterId,
            result.activeAppsStrategy.orEmpty(),
        )
        val localRank = SyncRank(
            fresh.activeStrategySyncRev,
            fresh.activeStrategyUpdatedAt,
            fresh.activeStrategyWriterId,
            currentId,
        )
        if (syncRankCompare(remoteRank, localRank) <= 0) return
        store.activeAppsStrategyConfigUuid = result.activeAppsStrategy?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        state.update { current ->
            if (current.activeStrategySyncRev > result.activeAppsStrategySyncRev) current
            else current.copy(
                activeStrategyUpdatedAt = result.activeAppsStrategyUpdatedAt,
                activeStrategySyncRev = result.activeAppsStrategySyncRev,
                activeStrategyWriterId = result.activeAppsStrategyWriterId,
            )
        }
    }
}

data class ApplyCounts(
    val downloaded: Int,
    val removed: Int,
    val skipped: Int,
)

private fun sameSubscription(left: ProfilePayload, right: ProfilePayload): Boolean {
    return subscriptionIdentity(left.type, left.source, left.uuid) ==
        subscriptionIdentity(right.type, right.source, right.uuid)
}

private fun usageStamp(upload: Long, download: Long, total: Long, expire: Long): String {
    return "$upload,$download,$total,$expire"
}

private fun readFiles(dir: File): List<ProfileFilePayload> {
    if (!dir.exists()) return emptyList()
    var total = 0
    return dir.walkTopDown().filter { it.isFile }.mapNotNull { file ->
        val rel = dir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        if (!isSyncedProfileFile(rel)) return@mapNotNull null
        if (file.length() > WEBDAV_MAX_FILE_BYTES) throw WebdavException("too_large", detail = rel)
        total += file.length().toInt()
        if (total > WEBDAV_MAX_PROFILE_BYTES) throw WebdavException("too_large", detail = rel)
        ProfileFilePayload(rel, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    }.sortedBy { it.path }.toList()
}

private fun fileUpdatedAt(context: Context, uuid: UUID, path: String): Long {
    return context.importedDir.resolve(uuid.toString()).resolve(path).lastModified()
}

