package io.olcos.app.core

import io.olcos.app.AppGraph
import io.olcos.app.R

/**
 * Making a room, in one place: the install form, the add-protocol dialog and
 * the room dialog all ask for a room the same way, and the room auto-update
 * (RoomRotation) makes its fresh rooms the same way. The outcome says whether
 * the room was made, whose sign-in the maker needs first — a kept one that was
 * refused counts as needing one again — or whether this protocol's room is
 * pasted by hand and nothing is ever made. MTS Link's room
 * is made by replaying what the app learned (MtsLinkReplay); where it cannot,
 * the sheet opens, with a reason. A refused sign-in is cleared here, so the
 * sheet that opens next starts clean.
 */
object RoomMint {
    /** Whose sign-in the maker needs before it can make a room. */
    enum class Service { YANDEX, VK, MTS }

    sealed class Outcome {
        /** The room was made. */
        data class Made(val room: String) : Outcome()

        /**
         * No sign-in is kept for [service], or the kept one was refused ([rejected]): the sheet opens.
         * [reason] (a string resource) is the one line that says why, where the sheet opens for another
         * cause than a missing sign-in (MTS Link's replay could not make the room).
         */
        data class SignIn(val service: Service, val rejected: Boolean, val reason: Int? = null) : Outcome()

        /** The app makes no room for this protocol: its room is pasted by hand. */
        data object Manual : Outcome()

        /** A room was made but cannot be used, and nothing else is to be done now; [reason] (a string resource) says why. */
        data class Refused(val reason: Int) : Outcome()
    }

    /**
     * A fresh room of [carrier] made with the kept sign-in of its service;
     * [near] is the room being replaced, which names the Jitsi server a fresh
     * Jitsi room is made on. What the service refuses or the network drops is
     * thrown for the caller to show.
     */
    suspend fun mint(carrier: String, near: String = ""): Outcome = when (carrier) {
        "jitsi" -> Outcome.Made(Maintenance.freshJitsiRoom(near))
        "telemost" -> {
            val yandex = AppGraph.yandex
            val session = yandex.session
            if (session == null) {
                Outcome.SignIn(Service.YANDEX, rejected = false)
            } else {
                try {
                    Outcome.Made(RoomServices.Telemost.create(session, yandex.twin))
                } catch (e: RoomServices.SessionRejected) {
                    yandex.clear()
                    Outcome.SignIn(Service.YANDEX, rejected = true)
                }
            }
        }
        "vkcalls" -> {
            val vk = AppGraph.vk
            val sid = vk.remixSid
            if (sid == null) {
                Outcome.SignIn(Service.VK, rejected = false)
            } else {
                try {
                    Outcome.Made(RoomServices.VKCall.create(sid))
                } catch (e: RoomServices.SessionRejected) {
                    vk.clear()
                    Outcome.SignIn(Service.VK, rejected = true)
                }
            }
        }
        // The request the app learned from the owner's own lesson, replayed silently from the
        // signed-in page. Anything the replay cannot do opens the sheet (where the owner makes
        // the meeting himself and the app still takes the link) with a one-line reason.
        "mtslink" -> mintMtsLink(near)
        else -> Outcome.Manual
    }

    private suspend fun mintMtsLink(near: String): Outcome {
        // Nothing is learned where the app graph does not exist (a unit test).
        val template = runCatching { AppGraph.mtsTemplate.template }.getOrNull()
        if (template == null) {
            AppLog.i(LOG_TAG, "make a room (MTS Link): nothing learned yet, opening the sheet")
            return Outcome.SignIn(Service.MTS, rejected = false, reason = R.string.mts_fallback_not_learned)
        }
        if (AppGraph.mts.credential == null) {
            AppLog.i(LOG_TAG, "make a room (MTS Link): no saved sign-in, opening the sheet")
            return Outcome.SignIn(Service.MTS, rejected = false, reason = R.string.mts_fallback_signed_out)
        }
        return when (val replay = MtsLinkReplay.make(template, near)) {
            // The made meeting must be one the server can enter, asked the way the core asks.
            is MtsLinkReplay.Result.Made -> when (val next = MtsMeetingCheck.afterReplay(MtsMeetingCheck.check(replay.link))) {
                MtsMeetingCheck.AfterReplay.Use -> {
                    // The meeting is this app's own now: the created-rooms list keeps its event id.
                    AppGraph.mtsMeetings.noteCreated(replay.link)
                    Outcome.Made(replay.link)
                }
                is MtsMeetingCheck.AfterReplay.Relearn -> {
                    AppGraph.mtsTemplate.forget()
                    AppLog.w(LOG_TAG, "make a room (MTS Link): the made meeting is ${MtsMeetingCheck.logWord(next.verdict)}; " +
                        "the template makes the wrong kind and was dropped, opening the sheet for a new lesson")
                    Outcome.SignIn(Service.MTS, rejected = false, reason = MtsMeetingCheck.refusalText(next.verdict))
                }
                is MtsMeetingCheck.AfterReplay.Retry -> {
                    AppLog.w(LOG_TAG, "make a room (MTS Link): the made meeting could not be checked; it is not used, the template stays")
                    Outcome.Refused(MtsMeetingCheck.refusalText(next.verdict) ?: R.string.mts_check_unknown)
                }
            }
            MtsLinkReplay.Result.SignedOut -> {
                AppLog.w(LOG_TAG, "make a room (MTS Link): signed out, opening the sheet")
                AppGraph.mts.clear()
                Outcome.SignIn(Service.MTS, rejected = true)
            }
            is MtsLinkReplay.Result.MissingToken -> {
                AppLog.w(LOG_TAG, "make a room (MTS Link): a token is missing, opening the sheet")
                Outcome.SignIn(Service.MTS, rejected = false, reason = R.string.mts_fallback_no_token)
            }
            is MtsLinkReplay.Result.Failed -> {
                AppLog.w(LOG_TAG, "make a room (MTS Link): the replay failed, opening the sheet")
                Outcome.SignIn(Service.MTS, rejected = false, reason = R.string.mts_fallback_failed)
            }
        }
    }

    private const val LOG_TAG = "olcos.rooms"
}
