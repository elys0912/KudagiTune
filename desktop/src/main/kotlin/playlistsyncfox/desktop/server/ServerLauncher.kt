package playlistsyncfox.desktop.server

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import playlistsyncfox.desktop.secrets.TlsIdentity
import java.security.KeyStore

/** 起動したサーバー。終了の手順（X5-6）が止める。 */
fun interface RunningServer {
    /** 送信中の接続の猶予は呼び出し側が待つので、ここは残りを切って止める。 */
    fun stop()
}

/** HTTPS の 1 ポートだけを開く（平文の HTTP は開かない。B4-3 の 1）。ポートが使用中なら例外を投げる。実環境の確認は C-13。 */
fun interface ServerLauncher {
    fun start(port: Int, tls: TlsIdentity, env: ServerEnv): RunningServer
}

class NettyServerLauncher : ServerLauncher {
    override fun start(port: Int, tls: TlsIdentity, env: ServerEnv): RunningServer {
        // 鍵と証明書からメモリ上で作る。ファイルへは書かない（X5-1）
        val password = "psfox-in-memory".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, tls.privateKey, password, arrayOf(tls.certificate))
        }
        val server: EmbeddedServer<*, *> = embeddedServer(
            Netty,
            configure = {
                sslConnector(keyStore, KEY_ALIAS, { password }, { password }) {
                    this.port = port
                }
            },
            module = { psfoxModule(env) },
        )
        server.start(wait = false)
        return object : RunningServer {
            override fun stop() = server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private companion object {
        const val KEY_ALIAS = "psfox"
    }
}
