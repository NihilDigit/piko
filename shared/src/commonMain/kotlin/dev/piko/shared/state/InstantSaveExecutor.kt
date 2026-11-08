package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantSaveReceipt
import dev.piko.shared.data.InstantSaveIncomplete
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import kotlinx.coroutines.flow.map

internal data class InstantRetryPlan(val target: PikoPathBreadcrumb, val files: List<InstantFileItem>,
        val names: Map<String, String>, val keepStructure: Boolean, val receipt: InstantSaveReceipt)

internal data class InstantExecutionResult(
    val response: Result<List<String>>,
    val receipt: InstantSaveReceipt,
    val confirmedPaths: Set<String>,
)

/** Receipts and side effects are bound to the submitted account and immutable plan. */
internal class InstantSaveExecutor(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val saveAccount: String,
) {
    suspend fun execute(
        plan: InstantRetryPlan,
        submitted: List<InstantFileItem>,
        targetIsFreshFolder: Boolean,
        reference: Boolean,
        previewed: Map<String, String>,
        referenceSource: String?,
        ledgerSource: String?,
        parentId: String?,
    ): InstantExecutionResult {
        val preResolvedFolders = if (!targetIsFreshFolder && plan.keepStructure) {
            val neededDirs = submitted.map { it.file }.filter { it.gcid != null }
                .flatMap { path ->
                    val dir = path.path.substringBeforeLast('/', "")
                    if (dir.isEmpty()) emptyList() else {
                        val parts = dir.split('/')
                        parts.indices.map { i -> parts.subList(0, i + 1).joinToString("/") }
                    }
                }.distinct()
            driveRepo.resolveExistingSubfoldersInTree(plan.target.id, neededDirs) + plan.receipt.folders
        } else plan.receipt.folders
        var resolvedFoldersSnapshot = preResolvedFolders + ("" to plan.target.id)
        val recordNewFolder: (String, String, String) -> Unit = { parentId, id, name ->
            if (driveRepo.currentAccount == saveAccount) {
                driveRepo.applyChange(dev.piko.shared.data.DriveChange.Created(parentId, id, name, isFolder = true))
            }
        }
        val result = if (reference) {
            val vault = driveRepo.vaultAccess(requireNotNull(saveAccount)) ?: throw kotlinx.coroutines.CancellationException(tr("账号会话已结束", "Account session ended", "アカウントのセッションが終了しました", "계정 세션이 종료되었습니다", "帳號工作階段已結束"))
            instantRepo.saveReferences(
                items = submitted.filter { it.file.gcid != null },
                targetParentId = plan.target.id,
                keepStructure = plan.keepStructure,
                names = plan.names,
                existingFolders = preResolvedFolders,
                vault = vault,
                source = referenceSource,
                targetIsFreshFolder = targetIsFreshFolder,
                knownMetaLookup = driveRepo::knownVaultMetaOf,
                onFolderCreated = recordNewFolder,
                onFoldersResolved = { resolvedFoldersSnapshot = it },
                onFreshVaultFolders = { freshIds ->
                    if (driveRepo.currentAccount == saveAccount) driveRepo.rememberFullyVaulted(freshIds)
                },
            ) { members ->
                if (driveRepo.currentAccount == saveAccount) driveRepo.vaultTrees.merge(members)
            }
        } else instantRepo.instantSave(
            submitted, plan.target.id, reuse = previewed, keepStructure = plan.keepStructure,
            names = plan.names, existingFolders = preResolvedFolders, onFolderCreated = recordNewFolder,
            onFoldersResolved = { resolvedFoldersSnapshot = it },
        )
        if (driveRepo.currentAccount != saveAccount) throw kotlinx.coroutines.CancellationException(tr("账号会话已结束", "Account session ended", "アカウントのセッションが終了しました", "계정 세션이 종료되었습니다", "帳號工作階段已結束"))
        val current = (result.exceptionOrNull() as? InstantSaveIncomplete)?.receipt ?: if (result.isSuccess) {
            InstantSaveReceipt(requireNotNull(saveAccount), plan.target.id,
                confirmed = submitted.filter { it.file.gcid != null }.map { it.file.path }.zip(result.getOrThrow()).toMap(),
                folders = resolvedFoldersSnapshot)
        } else {
            InstantSaveReceipt(requireNotNull(saveAccount), plan.target.id, failed = submitted.mapTo(HashSet()) { it.file.path },
                folders = resolvedFoldersSnapshot)
        }
        val attempted = submitted.mapTo(HashSet()) { it.file.path }
        val confirmed = plan.receipt.confirmed + current.confirmed
        val receipt = current.copy(confirmed = confirmed,
            unconfirmed = (plan.receipt.unconfirmed + current.unconfirmed) - confirmed.keys,
            failed = ((plan.receipt.failed - attempted) + current.failed) - confirmed.keys,
            notAttempted = ((plan.receipt.notAttempted - attempted) + current.notAttempted) - confirmed.keys,
            namingUnconfirmed = plan.receipt.namingUnconfirmed + current.namingUnconfirmed,
            unconfirmedFolder = plan.receipt.unconfirmedFolder ?: current.unconfirmedFolder,
            folders = plan.receipt.folders + current.folders)
        val touchedFolders = receipt.folders.values.toSet() + plan.target.id + listOfNotNull(parentId)
        if (reference && result.isSuccess) {
            driveRepo.notifyFoldersRefreshed(touchedFolders)
        } else {
            driveRepo.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(touchedFolders))
        }
        ledgerSource?.let { source ->
            driveRepo.sourceLedger.record(source, submitted.filter { it.file.path in current.confirmed }.map { it.file })
        }

        return InstantExecutionResult(result, receipt, current.confirmed.keys)
    }
}
