package com.arbelonson.ozen.core

import java.net.URI

object SharedRecordingLink {
    fun isAcceptable(link: String, ownPackage: String): Boolean {
        val parsed = try {
            URI(link)
        } catch (bad: Exception) {
            return false
        }
        if (parsed.scheme != "content") return false
        val authority = parsed.authority?.lowercase() ?: return false
        val own = ownPackage.lowercase()
        return authority != own && !authority.startsWith(own)
    }
}
