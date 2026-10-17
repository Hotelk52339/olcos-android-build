package io.olcos.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.olcos.app.R
import io.olcos.app.model.ConnectionRecord
import io.olcos.app.model.OlcrtcLink

/**
 * How a connection is named on the screens, as the iOS app does it
 * (ConnectionNaming.swift): the service is the headline, whose server it is
 * the line under it. No codec or transport names: they mean nothing to the
 * person connecting.
 */
object Naming {
    /** "VK Calls", "Whole server", or an Xray server's own name. */
    @Composable
    fun title(record: ConnectionRecord): String {
        val olcrtc = record.olcrtc ?: return record.displayName
        return if (olcrtc.isBundle) stringResource(R.string.bundle_service_name) else olcrtc.serviceName
    }

    /** Whose server: the list's label for it, without the service name the title already says. */
    @Composable
    fun subtitle(record: ConnectionRecord): String {
        val olcrtc = record.olcrtc ?: return stringResource(R.string.list_direct_subtitle)
        val host = host(record)
        return if (olcrtc.isBundle) stringResource(R.string.list_bundle_subtitle_fmt, host) else host
    }

    /** "ams-1 · Telemost" → "ams-1": the carrier is the headline and no line says it twice. */
    fun host(record: ConnectionRecord): String {
        val name = record.displayName.trim()
        val carrier = record.olcrtc?.carrier ?: return name
        val sep = name.lastIndexOf(" · ")
        if (sep <= 0) return name
        val prefix = name.substring(0, sep).trim()
        val tail = normalize(name.substring(sep + 3))
        if (prefix.isEmpty() || tail.isEmpty()) return name
        val candidates = listOf(normalize(carrier), normalize(OlcrtcLink.carrierLabel(carrier)))
        return if (tail in candidates || candidates.any { it.length >= 5 && (tail.contains(it) || it.contains(tail)) }) prefix else name
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
