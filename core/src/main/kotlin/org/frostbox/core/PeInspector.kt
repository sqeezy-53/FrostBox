package org.frostbox.core

import java.io.File
import java.io.RandomAccessFile

/** Identification only. This is not malware detection or proof an executable will run. */
object PeInspector {
    fun isGui(file: File): Boolean {
        inspect(file)
        return RandomAccessFile(file,"r").use { f ->
            f.seek(0x3c);val pe=Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
            f.seek(pe+20);val size=java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
            require(size>=70) { "PE optional header lacks subsystem" }
            f.seek(pe+24+68);(java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff)==2
        }
    }
    fun inspect(file: File): String = RandomAccessFile(file, "r").use { f ->
        require(f.length() >= 64) { "File is too short for a PE executable" }
        require(f.readUnsignedByte() == 0x4d && f.readUnsignedByte() == 0x5a) { "Missing MZ header" }
        f.seek(0x3c)
        val offset = Integer.reverseBytes(f.readInt()).toLong() and 0xffffffffL
        require(offset >= 64 && offset <= f.length() - 26) { "Invalid PE header offset" }
        f.seek(offset)
        require(f.readInt() == 0x50450000) { "Missing PE signature" }
        val machine = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        f.seek(offset + 20)
        val optionalSize = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        val flags = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        require(flags and 0x2000 == 0 && flags and 0x0002 != 0) { "Select an executable, not a DLL" }
        require(optionalSize >= 2 && offset + 24 + optionalSize <= f.length()) { "Truncated optional header" }
        val magic = java.lang.Short.reverseBytes(f.readShort()).toInt() and 0xffff
        when (machine) {
            0x8664 -> { require(magic == 0x20b); "x86-64" }
            0x14c -> { require(magic == 0x10b); "x86" }
            else -> error("Only Windows x86/x86-64 PE files are accepted")
        }
    }
}
