package dev.piko.smoke

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.upload.AndroidPikoUploadSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class UploadPermissionIdentitySmokeTest {
    @Test
    fun uploadReleasePreservesAReadWriteTreeOwnedByTheDownloadDestination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sources = AndroidPikoUploadSources(context)
        val received = AtomicReference<Uri?>(null)
        val tree = DocumentsContract.buildTreeDocumentUri(UploadGrantFixtureProvider.AUTHORITY, "root")
        val child = DocumentsContract.buildDocumentUriUsingTree(tree, "file")
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("download-grant", ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data ?: return@register
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    received.set(uri)
                }
                val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName
                launcher.launch(Intent().setComponent(ComponentName(fixturePackage, UploadGrantFixtureActivity::class.java.name)).putExtra("writable", true))
            }
            awaitCondition("保存位置持有读写授权") { received.get() != null }
            scenario.close()
            sources.retain(child.toString())
            sources.release(sources.permissionKey(child.toString()))
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
            assertNotNull(sources.describe(child.toString()))
            assertEquals("fixture", sources.readAt(child.toString(), 0, 7).decodeToString())
        } finally {
            scenario.close()
            runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
    @Test
    fun retainedTreeGrantOutlivesThePickerActivityAndReleaseActuallyRemovesReadAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sources = AndroidPikoUploadSources(context)
        val received = AtomicReference<Uri?>(null)
        val tree = DocumentsContract.buildTreeDocumentUri(UploadGrantFixtureProvider.AUTHORITY, "root")
        val child = DocumentsContract.buildDocumentUriUsingTree(tree, "file")
        val fixturePackage = InstrumentationRegistry.getInstrumentation().context.packageName
        assertTrue("测试提供器必须属于另一个 UID", context.applicationInfo.uid != context.packageManager.getApplicationInfo(fixturePackage, 0).uid)
        assertNull("测试提供器在授权前必须不可读", sources.describe(child.toString()))
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("upload-grant", ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data ?: return@register
                    sources.retain(uri.toString())
                    received.set(uri)
                }
                launcher.launch(Intent().setComponent(ComponentName(fixturePackage, UploadGrantFixtureActivity::class.java.name)))
            }
            awaitCondition("接收并保留测试提供器的树授权") { received.get() != null }
            assertEquals(tree, received.get())
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
            scenario.close()
            assertNotNull(sources.describe(child.toString()))
            assertEquals("fixture", sources.readAt(child.toString(), 0, 7).decodeToString())
            sources.release(sources.permissionKey(child.toString()))
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == tree })
            awaitCondition("授权释放且选择 Activity 结束后不能再读取提供器") { sources.describe(child.toString()) == null }
        } finally {
            scenario.close()
            sources.release(tree.toString())
        }
    }
    @Test
    fun treeDocumentsShareThePersistedTreePermissionIdentity() {
        val sources = AndroidPikoUploadSources(ApplicationProvider.getApplicationContext<Context>())
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:upload")
        val first = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:upload/one.bin")
        val second = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:upload/sub/two.bin")
        assertEquals(tree.toString(), sources.permissionKey(tree.toString()))
        assertEquals(tree.toString(), sources.permissionKey(first.toString()))
        assertEquals(tree.toString(), sources.permissionKey(second.toString()))
        val direct = Uri.parse("content://example.documents/document/single")
        assertEquals(direct.toString(), sources.permissionKey(direct.toString()))
    }
}
