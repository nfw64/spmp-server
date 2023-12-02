package spms.controller.modes

import spms.controller.SpMsControllerError
import spms.controller.ControllerMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.internal.decodeStringToJsonTree
import kotlinx.serialization.json.jsonNull
import kotlinx.serialization.json.jsonPrimitive
import libzmq.ZMQ_NOBLOCK
import spms.localisation.loc
import spms.serveraction.ServerAction
import kotlin.system.getTimeMillis

private const val SERVER_EVENT_TIMEOUT_MS: Long = 10000
private const val POLL_INTERVAL: Long = 100

class Poll: ControllerMode("poll", { "TODO" }) {
    override fun run() {
        super.run()

        runBlocking {
            connectSocket()

            log(currentContext.loc.cli.poll_polling_server_for_events)

            while (true) {
                delay(POLL_INTERVAL)

                val wait_end: Long = getTimeMillis() + SERVER_EVENT_TIMEOUT_MS
                var events: List<JsonElement>? = null

                while (events == null && getTimeMillis() < wait_end) {
                    events = socket
                        .recvStringMultipart(
                            (wait_end - getTimeMillis()).coerceAtLeast(ZMQ_NOBLOCK.toLong())
                        )
                        ?.mapNotNull { event ->
                            val string: String = event.removeSuffix("\u0000").takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null

                            return@mapNotNull Json.decodeFromString(string)
                        }
                }

                if (events == null) {
                    throw SpMsControllerError(currentContext.loc.cli.errServerDidNotSendEvents(SERVER_EVENT_TIMEOUT_MS))
                }

                if (events.isNotEmpty()) {
                    println(events)
                }

                socket.sendStringMultipart(listOf(""))
            }
        }

        releaseSocket()
    }
}
