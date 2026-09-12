package com.aptuidsh.kui.file

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 自建文件伺服 Provider(无第三方依赖,自研实现):
 * {@code content://com.aptuidsh.kui.files/<绝对路径>} → 只读文件流。
 *
 * <p>用途:把共享存储中的文件(APK/文本/压缩包…)以 content URI 交给系统应用打开
 * (ACTION_VIEW 需 FLAG_GRANT_READ_URI_PERMISSION)。读取受「所有文件访问」权限保护;
 * 任何内部异常一律转为 FileNotFoundException,不崩溃本进程,由调用方提示。
 */
class FileServeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "*/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = openRaw(uri, mode)

    private fun openRaw(uri: Uri, mode: String): ParcelFileDescriptor {
        val p = Uri.decode(uri.path ?: "") ?: ""
        if (p.isEmpty() || (mode != null && mode.contains("w"))) throw FileNotFoundException("unsupported: $p")
        val f = File(p)
        if (!f.isFile) throw FileNotFoundException("not a file: $p")
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException("unavailable: ${e.message}")
        }
    }

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String> = arrayOf("*/*")

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** 绝对路径 → 只读 content URI。 */
        fun uriFor(absPath: String): Uri =
            Uri.parse("content://${FileOpenKit.AUTHORITY}/" + Uri.encode(absPath, "/"))
    }
}
