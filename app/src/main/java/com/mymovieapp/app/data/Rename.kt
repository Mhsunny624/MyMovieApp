package com.mymovieapp.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.EnumSet

/** Renames the real video file (and the subtitle file beside it) on SMB shares, picked folders and device storage. */
object FileRenamer {
    class Result(val uri: String, val fileName: String, val subtitleUri: String?, val note: String?)

    private val BAD = Regex("""[\\/:*?"<>|]""")

    fun check(base: String): String? = when {
        base.isBlank() -> "The name can't be empty."
        BAD.containsMatchIn(base) -> "A file name can't contain \\ / : * ? \" < > |"
        base.length > 200 -> "That name is too long."
        base.endsWith(".") || base.endsWith(" ") -> "The name can't end with a dot or a space."
        else -> null
    }

    /** Throws IOException with a message that is safe to show to the user. */
    fun rename(ctx: Context, v: Video, newBase: String): Result {
        val ext = v.fileName.substringAfterLast('.', "")
        val oldBase = v.fileName.substringBeforeLast('.')
        val newName = if (ext.isEmpty()) newBase else "$newBase.$ext"
        if (newName == v.fileName) throw IOException("That's already the file's name.")

        val newUri = renameOne(ctx, Uri.parse(v.uri), newName)

        var subUri: String? = v.subtitleUri
        var note: String? = null
        v.subtitleUri?.let { s ->
            try {
                val su = Uri.parse(s)
                val subName = nameOf(ctx, su)
                if (subName != null && subName.startsWith(oldBase, ignoreCase = true)) {
                    subUri = renameOne(ctx, su, newBase + subName.substring(oldBase.length)).toString()
                } else note = "The subtitle file kept its old name."
            } catch (e: Exception) {
                note = "Renamed the video, but not its subtitle file (${e.message})."
            }
        }
        SubStore.move(ctx, v.uri, newUri.toString())
        return Result(newUri.toString(), newName, subUri, note)
    }

    private fun nameOf(ctx: Context, uri: Uri): String? =
        if (uri.scheme == "smb") Smb.split(uri).third.substringAfterLast('\\').ifBlank { null }
        else ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun renameOne(ctx: Context, uri: Uri, newName: String): Uri = when {
        uri.scheme == "smb" -> renameSmb(uri, newName)
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY -> renameMedia(ctx, uri, newName)
        uri.scheme == "content" -> renameSaf(ctx, uri, newName)
        else -> throw IOException("This kind of source can't be renamed.")
    }

    // ---- network share ----
    private fun smbMessage(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "ACCESS_DENIED" in m -> "The PC refused: your Windows account only has read access to this share. Give it \"Change\" permission (in the share's permissions and in the folder's security tab) and try again."
            "SHARING_VIOLATION" in m -> "The file is in use (open on the PC or in another player). Close it and try again."
            "COLLISION" in m -> "A file with that name already exists."
            else -> m.ifBlank { "Rename failed" }
        }
    }

    private fun renameSmb(uri: Uri, newName: String): Uri {
        val (h, s, rel) = Smb.split(uri)
        val parent = rel.substringBeforeLast('\\', "")
        val newRel = if (parent.isEmpty()) newName else "$parent\\$newName"
        fun attempt() {
            val share = Smb.share(h, s)
            if (share.fileExists(newRel)) throw IOException("A file named \"$newName\" already exists in that folder.")
            share.openFile(
                rel, EnumSet.of(AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
            ).use { it.rename(newRel, false) }
        }
        try {
            try {
                attempt()
            } catch (e: SMBApiException) { throw e
            } catch (e: IOException) { throw e
            } catch (e: Exception) { Smb.invalidate(h, s); attempt() }   // stale connection: retry once
        } catch (e: IOException) { throw e
        } catch (e: Exception) { throw IOException(smbMessage(e), e) }
        return Smb.uri(h, s, newRel)
    }

    // ---- folders picked with the system picker ----
    private fun renameSaf(ctx: Context, uri: Uri, newName: String): Uri {
        val isTree = uri.pathSegments.firstOrNull() == "tree"
        val out = try {
            DocumentsContract.renameDocument(ctx.contentResolver, uri, newName)
        } catch (e: SecurityException) {
            throw IOException(
                if (isTree) "No write permission for this folder yet. Remove it in Settings → Libraries, add it again, and allow changes when Android asks."
                else "Videos picked one by one are read-only for this app. Add their folder as a library folder instead."
            )
        } catch (e: FileNotFoundException) {
            throw IOException("The file isn't there any more.")
        } catch (e: UnsupportedOperationException) {
            throw IOException("This storage doesn't allow renaming.")
        } catch (e: Exception) {
            throw IOException(e.message ?: "Android refused to rename it.")
        } ?: throw IOException("Android refused to rename it.")
        // Keep the same address style the scanner uses, so a rescan doesn't see a new file.
        return if (isTree) {
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(out))
        } else out
    }

    // ---- device storage / USB (MediaStore) ----
    private fun renameMedia(ctx: Context, uri: Uri, newName: String): Uri {
        try {
            val n = ctx.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null
            )
            if (n < 1) throw IOException("Android didn't rename it.")
        } catch (e: SecurityException) {
            throw IOException("Android doesn't let this app rename files on device storage that other apps created. Rename it in your phone's Files app, or add its folder as a picked folder or network share.")
        }
        return uri
    }
}