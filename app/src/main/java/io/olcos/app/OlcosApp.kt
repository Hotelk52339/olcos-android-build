package io.olcos.app

import android.app.Application
import android.content.Context
import io.olcos.app.core.Notifications
import io.olcos.app.core.ServerPairing
import io.olcos.app.core.SessionRefresh
import io.olcos.app.core.TunnelManager
import io.olcos.app.data.AppSettings
import io.olcos.app.data.ConnectionStore
import io.olcos.app.data.MTSLinkMeetings
import io.olcos.app.data.MTSLinkSession
import io.olcos.app.data.MTSLinkTemplate
import io.olcos.app.data.RoomAges
import io.olcos.app.data.ServerSecrets
import io.olcos.app.data.ServerStore
import io.olcos.app.data.VKSession
import io.olcos.app.data.YandexSession
import io.olcos.app.ui.importSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The app-wide objects, made once with the process. */
object AppGraph {
    /**
     * Work that must outlive the screen that started it (an import, a server
     * script, an invite upload): Back or a rotation cancels a screen's own
     * scope, not this one. Lives as long as the process.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var store: ConnectionStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var servers: ServerStore
        private set
    lateinit var yandex: YandexSession
        private set
    lateinit var vk: VKSession
        private set
    lateinit var mts: MTSLinkSession
        private set
    lateinit var mtsMeetings: MTSLinkMeetings
        private set
    lateinit var mtsTemplate: MTSLinkTemplate
        private set
    lateinit var roomAges: RoomAges
        private set
    lateinit var secrets: ServerSecrets
        private set
    /** Whether this process has joined the chosen connection on its own already: once per launch, as on iOS. */
    var didAutoConnect = false

    /** The application context, for the widgets and the tile; null in tests. */
    var appContext: Context? = null
        private set

    fun init(app: Application) {
        appContext = app.applicationContext
        store = ConnectionStore(app)
        servers = ServerStore(app)
        yandex = YandexSession(app)
        vk = VKSession(app)
        // WB Stream is gone from the app: a token an earlier version saved is a credential nothing can use or sign out of now.
        for (name in listOf("olcos.wb", "olcos.wb.secure")) runCatching { app.deleteSharedPreferences(name) }
        mts = MTSLinkSession(app)
        mtsMeetings = MTSLinkMeetings(app)
        mtsTemplate = MTSLinkTemplate(app)
        // After the MTS Link stores: the settings resolve each room rule as they are made, and an MTS Link
        // schedule holds only while the app can make meetings (RoomServices.MTSLink.canCreate reads both).
        settings = AppSettings(app)
        // The in-app update check is gone (a store build must not offer its own updates): drop what it kept.
        runCatching { app.deleteSharedPreferences("olcos.updates") }
        roomAges = RoomAges(app)
        secrets = ServerSecrets(app)
    }
}

class OlcosApp : Application() {
    private companion object {
        /** Tries per session for a pairing still waiting: the first one and nine a minute apart. */
        const val PAIRING_TRIES = 10
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.ensureChannel(this)
        // A whole-server lane that stays out of its room looks its room up again, also with no screen open.
        SessionRefresh.install(this)
        SessionRefresh.refreshList = { link -> importSource(this, AppGraph.store, link) }
        // Access an invite could not confirm goes through once a session runs
        // through that server: at once, then every minute while one still
        // waits (rooms still settling may not carry the first try).
        AppGraph.scope.launch {
            TunnelManager.state.collectLatest { state ->
                if (state !is TunnelManager.State.Connected) return@collectLatest
                repeat(PAIRING_TRIES) {
                    ServerPairing.retryPending(AppGraph.servers)
                    if (AppGraph.servers.servers.value.none { !it.isPaired && it.pendingCode != null }) return@collectLatest
                    delay(60_000)
                }
            }
        }
    }
}
