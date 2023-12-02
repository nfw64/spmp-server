package spms

import ICON_BYTES
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import cinterop.indicator.TrayIndicator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import libappindicator.*
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import spms.localisation.SpMsLocalisation
import spms.localisation.loc
import kotlin.system.exitProcess

const val DEFAULT_PORT: Int = 3973
const val DEFAULT_ADDRESS: String = "127.0.0.1"
const val BUG_REPORT_URL: String = "https://github.com/toasterofbread/spmp-server/issues"
private const val POLL_INTERVAL_MS: Long = 100
private const val CLIENT_REPLY_TIMEOUT_MS: Long = 1000

@Suppress("OPT_IN_USAGE")
@OptIn(ExperimentalForeignApi::class)
fun createIndicator(coroutine_scope: CoroutineScope, loc: SpMsLocalisation, endProgram: () -> Unit): TrayIndicator? {
    val icon_path: Path =
        when (Platform.osFamily) {
            OsFamily.LINUX -> "/tmp/ic_spmp.png".toPath()
            else -> throw NotImplementedError(Platform.osFamily.name)
        }

    if (!FileSystem.SYSTEM.exists(icon_path)) {
        FileSystem.SYSTEM.write(icon_path) {
            write(ICON_BYTES.toByteString())
        }
    }

    val indicator: TrayIndicator? = TrayIndicator.create("SpMs", icon_path.segments)
    indicator?.apply {
        addButton(loc.server.indicator_button_open_client) {
            coroutine_scope.launch(Dispatchers.Default) {
                popen("spmp", "r")
            }
        }

        addButton(loc.server.indicator_button_stop_server) {
            endProgram()
        }
    }

    return indicator
}

@OptIn(ExperimentalForeignApi::class)
class SpMsCommand: Command(
    name = "spms",
    help = { cli.command_help_root },
    is_default = true
) {
    private val port: Int by option("-p", "--port").int().default(DEFAULT_PORT).help { context.loc.server.option_help_port }
    private val enable_gui: Boolean by option("-g", "--gui").flag().help { context.loc.server.option_help_gui }
    private val mute_on_start: Boolean by option("-m", "--mute").flag().help { context.loc.server.option_help_mute }

    override fun run() {
        super.run()

        if (halt) {
            exitProcess(0)
        }

        if (currentContext.invokedSubcommand != null) {
            return
        }

        var stop: Boolean = false

        memScoped {
            val server = SpMs(this, !enable_gui)
            server.bind(port)

            if (mute_on_start) {
                server.mpv.setVolume(0f)
            }

            runBlocking {
                val indicator: TrayIndicator? = createIndicator(this, localisation) {
                    stop = true
                }

                if (indicator != null) {
                    launch(Dispatchers.Default) {
                        indicator.show()
                    }
                }

                println("--- ${localisation.server.polling_started} ---")
                while (server.poll(CLIENT_REPLY_TIMEOUT_MS) && !stop) {
                    delay(POLL_INTERVAL_MS)
                }
                println("--- ${localisation.server.polling_ended} ---")

                server.release()
                indicator?.release()
                kill(0, SIGTERM)
            }
        }
    }

    companion object {
        const val application_name: String = "spmp-server"
    }
}
