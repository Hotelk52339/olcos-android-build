package io.olcos.app

import android.app.Application
import android.content.Context
import io.olcos.app.core.Notifications
import io.olcos.app.core.ServerPairing
import io.olcos.app.core.TunnelManager
import io.olcos.app.core.UpdateChecker
import io.olcos.app.data.AppSettings
import io.olcos.app.data.ConnectionStore
import io.olcos.app.data.RoomAges
import io.olcos.app.data.ServerSecrets
import io.olcos.app.data.ServerStore
import io.olcos.app.data.VKSession
import io.olcos.app.data.WBSession
import io.olcos.app.data.YandexSession
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
    lateinit var wb: WBSession
        private set
    lateinit var updates: UpdateChecker
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
        settings = AppSettings(app)
        servers = ServerStore(app)
        yandex = YandexSession(app)
        vk = VKSession(app)
        wb = WBSession(app)
        updates = UpdateChecker(app)
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
