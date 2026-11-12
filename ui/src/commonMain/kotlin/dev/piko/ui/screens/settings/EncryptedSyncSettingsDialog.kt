package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.sync.EncryptedSyncStatus
import dev.piko.ui.components.PikoTextField

/**
 * 独立加密同步口令设置与远端密文冲突恢复对话框。
 *
 * Google 登录不含账号密码；设置此口令后，解压密码与 MetaTube 配置以 PBKDF2-HMAC-SHA256 派生密钥加密同步至 `.piko`（信封版本 v2）。
 * 若云端密文因修改过密码或另一台设备设置了不同口令而无法解密，不会自动覆盖云端，仅允许输入匹配口令解锁，或显式备份后重置。
 */
@Composable
internal fun EncryptedSyncSettingsDialog(
    status: EncryptedSyncStatus,
    hasPassphrase: Boolean,
    busy: Boolean,
    mismatchError: String? = null,
    onClearMismatchError: () -> Unit = {},
    onSavePassphrase: (String) -> Unit,
    onClearPassphrase: () -> Unit,
    onResetCloud: () -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    SettingsInputDialog(
        icon = Icons.Outlined.Lock,
        title = tr("加密同步口令", "Encrypted sync passphrase", "暗号化同期パスフレーズ", "암호화 동기화 암호문구", "加密同步口令"),
        description = tr(
            "用于跨设备端到端加密同步「解压密码」与「MetaTube 配置」。在所有设备上输入同一口令即可自动解锁合并；口令仅保存在本机系统机密库，不会上传。",
            "Encrypts saved archive passwords and MetaTube settings across devices. Enter the same passphrase on each device to unlock and merge; the passphrase stays in your local OS keychain.",
            "「解凍パスワード」と「MetaTube設定」を端末間で暗号化同期します。各端末で同じパスフレーズを入力すると復号・統合されます。パスフレーズは端末のキーチェーンにのみ保存されます。",
            "'압축 해제 비밀번호'와 'MetaTube 설정'을 기기 간 암호화 동기화합니다. 각 기기에 동일한 암호문구를 입력하면 잠금 해제 및 병합되며, 암호문구는 기기 보안 저장소에만 보관됩니다.",
            "用於跨裝置端對端加密同步「解壓縮密碼」與「MetaTube 設定」。在所有裝置上輸入同一口令即可自動解鎖合併；口令僅儲存在本機系統機密庫，不會上傳。",
        ),
        onSave = { onSavePassphrase(passphrase) },
        saveEnabled = !busy && passphrase.isNotEmpty(),
        onDismiss = onDismiss,
    ) {
        if (status == EncryptedSyncStatus.LockedKeyMismatch || mismatchError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tr(
                            "云端存在用其他密码或口令加密的数据，Piko 已暂停覆盖以保护远端内容。请在下方输入原口令保存以解锁合并；若已遗忘原口令，可先自动备份旧密文再用本机数据重置云端。",
                            "Cloud sync data is encrypted with a different password or passphrase. Piko paused writes to protect it. Enter the matching passphrase below to unlock, or back up the old ciphertext and reset from this device.",
                            "クラウドに別のパスワード／パスフレーズで暗号化されたデータがあるため、保護のため上書きを一時停止しています。元のパスフレーズを入力して保存するか、旧データをバックアップしてこの端末のデータでリセットしてください。",
                            "클라우드에 다른 비밀번호 또는 암호문구로 암호화된 데이터가 있어 보호를 위해 덮어쓰기를 일시 중지했습니다. 아래에 기존 암호문구를 입력해 저장하거나, 기존 암호문을 백업한 뒤 현재 기기 데이터로 초기화하세요.",
                            "雲端存在以其他密碼或口令加密的資料，Piko 已暫停覆寫以保護遠端內容。請在下方輸入原口令儲存以解鎖合併；若已遺忘原口令，可先自動備份舊密文再以本機資料重設雲端。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!confirmReset) {
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            enabled = !busy,
                        ) {
                            Text(
                                tr(
                                    "用本机数据重置云端（自动保留备份）",
                                    "Reset cloud copy from this device (keeps backup)",
                                    "この端末のデータでリセット（バックアップを保持）",
                                    "이 기기 데이터로 클라우드 초기화 (백업 보관)",
                                    "以本機資料重設雲端（自動保留備份）",
                                ),
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onResetCloud,
                                enabled = !busy,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text(tr("确认备份并重置", "Confirm backup & reset", "バックアップしてリセット", "백업 및 초기화 확인", "確認備份並重設"))
                            }
                            TextButton(onClick = { confirmReset = false }, enabled = !busy) {
                                Text(tr("取消", "Cancel", "キャンセル", "취소", "取消"))
                            }
                        }
                    }
                }
            }
        }

        PikoTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                if (mismatchError != null) onClearMismatchError()
            },
            label = if (hasPassphrase) {
                tr("更换同步口令", "New sync passphrase", "新しい同期パスフレーズ", "새 동기화 암호문구", "更換同步口令")
            } else {
                tr("设置同步口令", "Sync passphrase", "同期パスフレーズ", "동기화 암호문구", "設定同步口令")
            },
            isError = mismatchError != null,
            supportingText = mismatchError ?: if (hasPassphrase) {
                tr("当前已设置独立同步口令", "An independent sync passphrase is currently set", "現在、独自の同期パスフレーズが設定されています", "현재 별도의 동기화 암호문구가 설정되어 있습니다", "目前已設定獨立同步口令")
            } else {
                tr("输入后点击保存立即生效并触发加密同步", "Enter a passphrase and save to unlock or start encrypted sync", "入力して保存すると直ちに暗号化同期を開始します", "입력 후 저장하면 즉시 암호화 동기화가 실행됩니다", "輸入後點選儲存立即生效並觸發加密同步")
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        if (hasPassphrase) {
            OutlinedButton(
                onClick = onClearPassphrase,
                enabled = !busy,
            ) {
                Text(
                    tr(
                        "清除本机独立同步口令",
                        "Clear custom passphrase on this device",
                        "この端末の独自パスフレーズを消去",
                        "이 기기의 별도 암호문구 지우기",
                        "清除本機獨立同步口令",
                    ),
                )
            }
        }
    }
}
