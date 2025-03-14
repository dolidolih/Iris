// ConfigPageDocumentProvider.java
package party.qwer.iris

class PageRenderer {
    companion object {
        fun renderDashboard(): String {
            var html = AssetManager.readFile("dashboard.html")
            html = html.replace("CURRENT_WEB_ENDPOINT", Configurable.webServerEndpoint)
            html = html.replace("CURRENT_BOT_NAME", Configurable.botName)
            html = html.replace("CURRENT_DB_RATE", Configurable.dbPollingRate.toString())
            html = html.replace("CURRENT_SEND_RATE", Configurable.messageSendRate.toString())
            html = html.replace("CURRENT_BOT_PORT", Configurable.botSocketPort.toString())
            return html
        }
    }
}