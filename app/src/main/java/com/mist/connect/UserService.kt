package com.mist.connect

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs in Shizuku privileged process. AIDL methods execute as shell-level.
 */
class UserService : IUserService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return "empty command"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    out.append(line).append('\n')
                }
            }
            BufferedReader(InputStreamReader(p.errorStream)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    out.append(line).append('\n')
                }
            }
            val code = p.waitFor()
            val text = out.toString().trimEnd()
            if (text.isEmpty()) "(exit $code, empty)" else text
        } catch (e: Exception) {
            "exec error: ${e.message}"
        }
    }
}
