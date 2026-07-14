package com.example.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object TargetStorage {
    private const val DIR_NAME = "chain_targets"

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveTarget(context: Context, bitmap: Bitmap): File {
        val fileName = "target_${System.currentTimeMillis()}.png"
        val file = File(getDir(context), fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    fun listTargets(context: Context): List<File> {
        val dir = getDir(context)
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: emptyArray()
        return files.sortedBy { it.name }
    }

    fun deleteTarget(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
        }
    }

    fun loadBitmap(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }
}
