package com.facegate.security

import at.favre.lib.crypto.bcrypt.BCrypt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a typed-in password against a bcrypt hash synced down from the
 * backend (admin_user.password_hash — same hash the website checks at
 * login, see AdminRepository.ts / AuthUserEntity's doc comment).
 *
 * ⚠️ REQUIRES A NEW GRADLE DEPENDENCY this project doesn't have yet:
 *     implementation("at.favre.lib:bcrypt:0.10.2")
 * That's a small, pure-Java, Android-friendly bcrypt implementation (no
 * native code, no extra crypto provider setup) — add it to the app
 * module's build.gradle. build.gradle wasn't part of what was uploaded
 * here, so this file can't add the dependency itself; until it's added,
 * this file won't compile.
 */
@Singleton
class PasswordVerifier @Inject constructor() {

    /**
     * True if [plainPassword] hashes to [bcryptHash]. Constant-time
     * comparison is handled inside the bcrypt library itself — don't
     * replace this with a manual string == check.
     */
    fun matches(plainPassword: String, bcryptHash: String): Boolean {
        if (plainPassword.isEmpty() || bcryptHash.isEmpty()) return false
        return try {
            BCrypt.verifyer().verify(plainPassword.toCharArray(), bcryptHash).verified
        } catch (e: IllegalArgumentException) {
            // Malformed/empty hash (e.g. a row that somehow synced down
            // without one) — treat as "doesn't match" rather than crashing
            // the login dialog.
            false
        }
    }
}
