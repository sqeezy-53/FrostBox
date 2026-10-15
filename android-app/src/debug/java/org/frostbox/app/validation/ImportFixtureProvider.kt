package org.frostbox.app.validation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Debug-only, non-exported read-only provider. Tests exercise real ContentResolver I/O. */
class ImportFixtureProvider: ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri: Uri)="application/vnd.microsoft.portable-executable"
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?)=
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("../../external\\malicious.exe")) }
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
        require(uri.path=="/exe" && mode=="r")
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir,"import-validation.exe"),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri?=throw UnsupportedOperationException()
    override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?): Int=throw UnsupportedOperationException()
    override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?): Int=throw UnsupportedOperationException()
}
