// ConfigPageDocumentProvider.java
package party.qwer.iris

import party.qwer.iris.Configurable.botName
import party.qwer.iris.Configurable.botSocketPort
import party.qwer.iris.Configurable.dbPollingRate
import party.qwer.iris.Configurable.messageSendRate
import party.qwer.iris.Configurable.webServerEndpoint
import java.io.IOException

object PageRenderer {
    @Throws(IOException::class)
    fun renderDashboard(): String {
        var html = AssetManager.readFile("dashboard.html")
        html = html.replace("CURRENT_WEB_ENDPOINT", webServerEndpoint)
        html = html.replace("CURRENT_BOT_NAME", botName)
        html = html.replace("CURRENT_DB_RATE", dbPollingRate.toString())
        html = html.replace("CURRENT_SEND_RATE", messageSendRate.toString())
        html = html.replace("CURRENT_BOT_PORT", botSocketPort.toString())
        return html
    }
}