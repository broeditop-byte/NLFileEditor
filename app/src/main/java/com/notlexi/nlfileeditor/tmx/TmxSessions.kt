package com.notlexi.nlfileeditor.tmx

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Keeps terminal sessions alive for the life of the app process, so leaving the terminal screen or
 * rotating the phone does not kill a running command. A session that has exited is replaced on request.
 */
object TmxSessions {
    @Volatile var light: TerminalSession? = null
    @Volatile var full: TerminalSession? = null

    private const val TRANSCRIPT_ROWS = 4000

    // ------------------------------------------------------------------------- NL TMX Light

    /**
     * Android's own shell (/system/bin/sh, mksh) on a real pseudo-terminal: line editing, history
     * (arrow keys), job control keys (^C ^Z), full-screen programs and colours work like in any terminal.
     * It has the privileges of this app only: no root, and no access to other apps' private data.
     */
    fun lightSession(context: Context, client: TerminalSessionClient, forceNew: Boolean = false): TerminalSession {
        val existing = light
        if (!forceNew && existing != null && existing.isRunning) {
            existing.updateTerminalSessionClient(client)
            return existing
        }
        existing?.finishIfRunning()
        val home = File(context.filesDir, "tmx_light").apply { mkdirs() }
        val rc = File(home, ".mkshrc")
        rc.writeText(
            "set -o emacs\n" +                       // arrow keys + line editing
                "HISTFILE='${home.path}/.mksh_history'\nHISTSIZE=2000\n" +
                "PS1='nl-tmx:\$PWD \\$ '\n" +
                "alias ll='ls -la'\n"
        )
        val tmp = File(context.cacheDir, "tmx").apply { mkdirs() }
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=en_US.UTF-8",
            "HOME=${home.path}", "TMPDIR=${tmp.path}", "ENV=${rc.path}",
            "PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin",
            "ANDROID_ROOT=/system", "ANDROID_DATA=/data", "EXTERNAL_STORAGE=/sdcard",
            "APP_FILES=${context.filesDir.path}"
        )
        val s = TerminalSession("/system/bin/sh", home.path, arrayOf("sh"), env, TRANSCRIPT_ROWS, client)
        light = s
        return s
    }

    // ------------------------------------------------------------------------- NL TMX Full

    /**
     * Alpine Linux in [RootfsInstaller.rootfs], entered with proot. proot is shipped as a native
     * library (libproot.so + its loader), the only place Android lets an app execute files from.
     * It fakes root (-0) and bind-mounts /dev, /proc and /sys; it does NOT give real root.
     */
    fun fullSession(context: Context, client: TerminalSessionClient, compatibilityMode: Boolean, forceNew: Boolean = false): TerminalSession {
        val existing = full
        if (!forceNew && existing != null && existing.isRunning) {
            existing.updateTerminalSessionClient(client)
            return existing
        }
        existing?.finishIfRunning()
        val installer = RootfsInstaller(context)
        val lib = context.applicationInfo.nativeLibraryDir
        val proot = File(lib, "libproot.so")
        val loader = File(lib, "libproot-loader.so")
        val tmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

        val args = arrayListOf(
            "proot", "--kill-on-exit", "--link2symlink", "-0",
            "-r", installer.rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/sh",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "/bin/sh", "-l"
        )
        val env = arrayListOf(
            "PROOT_LOADER=${loader.path}",
            "PROOT_TMP_DIR=${tmp.path}",
            "TMPDIR=${tmp.path}",
            "PATH=/system/bin:/system/xbin"
        )
        val loader32 = File(lib, "libproot-loader32.so")
        if (loader32.exists()) env.add("PROOT_LOADER_32=${loader32.path}")
        if (compatibilityMode) env.add("PROOT_NO_SECCOMP=1")

        val s = TerminalSession(proot.path, context.filesDir.path, args.toTypedArray(), env.toTypedArray(), TRANSCRIPT_ROWS, client)
        full = s
        return s
    }

    fun fullEntryPossible(context: Context): String? {
        val lib = context.applicationInfo.nativeLibraryDir
        if (!File(lib, "libproot.so").canExecute()) {
            return "proot is not available for this phone's CPU in this build."
        }
        return null
    }
}
