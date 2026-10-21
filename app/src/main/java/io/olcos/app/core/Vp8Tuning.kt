package io.olcos.app.core

/**
 * The VP8 numbers a connection actually runs on. MTS Link carries vp8channel
 * alone and its relay passes about as much as the frame rate allows: a frame
 * leaves every 1/fps seconds with up to `batch_size` packets in it, so the
 * ceiling grows with both. The core's release gate measures MTS Link at 60
 * frames/s and 64 packets per frame, and the app's 30/64 default leaves half
 * of that on the table. Every other protocol keeps the person's numbers.
 */
object Vp8Tuning {
    /** The frame rate and the packets per frame MTS Link needs at least. */
    const val MTS_LINK_FPS = 60
    const val MTS_LINK_BATCH = 64

    data class Numbers(val fps: Int, val batch: Int)

    /** PURE: [userFPS] / [userBatch] as they run when any of [carriers] is MTS Link: 60 frames/s and at least 64 packets; otherwise untouched. */
    fun effective(carriers: Collection<String>, userFPS: Int, userBatch: Int): Numbers =
        if ("mtslink" in carriers) Numbers(MTS_LINK_FPS, maxOf(userBatch, MTS_LINK_BATCH)) else Numbers(userFPS, userBatch)
}
