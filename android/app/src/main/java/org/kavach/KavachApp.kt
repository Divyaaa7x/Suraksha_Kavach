package org.kavach

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.kavach.data.KavachDb
import org.kavach.pack.Pack
import org.kavach.pack.PackLoader
import org.kavach.service.KavachNode
import org.kavach.sync.LanSync

/** Pack loading state. The real pack is ~16 MB, so it is verified on a background thread. */
sealed interface PackState {
    data object Loading : PackState
    data class Ready(val pack: Pack, val node: KavachNode, val sync: LanSync) : PackState
    data class Failed(val message: String) : PackState
}

/**
 * Process-wide container. The OpenStreetMap pack ships inside the APK under assets/pack and is
 * verified on every start (signature and every file hash) before any screen can use it.
 */
class KavachApp : Application() {

    lateinit var db: KavachDb
        private set

    private val _state = MutableStateFlow<PackState>(PackState.Loading)
    val packState: StateFlow<PackState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, KavachDb::class.java, "kavach.db").build()
        Thread({
            _state.value = try {
                val pack = PackLoader.load({ name -> assets.open("pack/$name").use { it.readBytes() } })
                val node = KavachNode(pack, db.events())
                PackState.Ready(pack, node, LanSync(node))
            } catch (e: Exception) {
                PackState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }, "pack-loader").start()
    }
}
