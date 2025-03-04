// ConfigPageDocumentProvider.java
package party.qwer.iris;

public class ConfigPageDocumentProvider {

    public static final String CONFIG_PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Iris Test UI</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; background-color: #f4f4f4; }
                    h1 { color: #333; }
                    .config-section { background-color: #fff; padding: 20px; margin-bottom: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
                    .section { background-color: #fff; padding: 20px; margin-bottom: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
                    label { display: block; margin-bottom: 5px; font-weight: bold; }
                    input[type='text'], input[type='number'], textarea, select { width: 100%; padding: 8px; margin-bottom: 10px; border: 1px solid #ddd; border-radius: 4px; box-sizing: border-box; }
                    button { background-color: #5cb85c; color: white; padding: 10px 15px; border: none; border-radius: 4px; cursor: pointer; }
                    button:hover { background-color: #4cae4c; }
                    .status-section { margin-top: 20px; padding: 10px; border-radius: 5px; background-color: #e0e0e0; }
                    .status-good { color: green; }
                    .status-bad { color: red; }
                    .log-table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                    .log-table th, .log-table td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                    .log-table th { background-color: #f0f0f0; }
                    #responseArea { white-space: pre-wrap; background-color: #eee; padding: 10px; border-radius: 4px; margin-top: 10px; }
                    .config-notice { color: orange; font-weight: bold; margin-top: 10px; }
                </style>
            </head>
            <body>
                <h1>Iris Test UI</h1>

                <div class="config-section">
                    <h2>Configurations</h2>

                    <div>
                        <h3>Bot Name</h3>
                        <label for="botName">Current Bot Name:</label>
                        <input type="text" id="botName" value="CURRENT_BOT_NAME" readonly>
                        <label for="newBotName">New Bot Name:</label>
                        <input type="text" id="newBotName" placeholder="Enter new bot name">
                        <button onclick="updateConfig('botname', document.getElementById('newBotName').value)">Update Bot Name</button>
                    </div>
                    <div>
                        <h3>Web Server Endpoint</h3>
                        <label for="webServerEndpoint">Current Endpoint:</label>
                        <input type="text" id="webServerEndpoint" value="CURRENT_WEB_ENDPOINT" readonly>
                        <label for="newWebServerEndpoint">New Endpoint:</label>
                        <input type="text" id="newWebServerEndpoint" placeholder="Enter new endpoint">
                        <button onclick="updateConfig('endpoint', document.getElementById('newWebServerEndpoint').value)">Update Endpoint</button>
                    </div>

                    <div>
                        <h3>DB Polling Rate</h3>
                        <label for="dbPollingRate">Current Rate (ms):</label>
                        <input type="number" id="dbPollingRate" value="CURRENT_DB_RATE" readonly>
                        <label for="newDbPollingRate">New Rate (ms):</label>
                        <input type="number" id="newDbPollingRate" placeholder="Enter new DB polling rate">
                        <button onclick="updateConfig('dbrate', document.getElementById('newDbPollingRate').value)">Update DB Rate</button>
                    </div>

                    <div>
                        <h3>Message Send Rate</h3>
                        <label for="messageSendRate">Current Rate (ms):</label>
                        <input type="number" id="messageSendRate" value="CURRENT_SEND_RATE" readonly>
                        <label for="newMessageSendRate">New Rate (ms):</label>
                        <input type="number" id="newMessageSendRate" placeholder="Enter new message send rate">
                        <button onclick="updateConfig('sendrate', document.getElementById('newMessageSendRate').value)">Update Send Rate</button>
                    </div>

                    <div>
                        <h3>Bot Port</h3>
                        <label for="botPort">Current Port:</label>
                        <input type="number" id="botPort" value="CURRENT_BOT_PORT" readonly>
                        <label for="newBotPort">New Port:</label>
                        <input type="number" id="newBotPort" placeholder="Enter new bot port">
                        <button onclick="updateConfig('botport', document.getElementById('newBotPort').value)">Update Bot Port</button>
                        <p class="config-notice">Notice: Server restart required for bot port changes to take effect.</p>
                    </div>
                </div>


                <div class="section">
                    <h2>/reply Endpoint Test</h2>
                    <form id="replyForm">
                        <label for="replyRoom">Room ID:</label>
                        <input type="text" id="replyRoom" name="room" required>

                        <label for="replyType">Message Type:</label>
                        <select id="replyType" name="type" onchange="updateReplyDataPlaceholder()">
                            <option value="text">Text</option>
                            <option value="image">Image (Base64)</option>
                            <option value="image_multiple">Multiple Images (Base64 JSON Array)</option>
                        </select>

                        <label for="replyData">Message Data:</label>
                        <textarea id="replyData" name="data" rows="4" required placeholder="Enter message text"></textarea>

                        <label for="replyRawJson">Raw JSON Body (override above):</label>
                        <textarea id="replyRawJson" name="rawJson" rows="4" placeholder='{"room": "", "type": "text", "data": ""}''></textarea>

                        <button type="button" onclick="submitForm('/reply', 'replyForm', 'replyResponseArea')">Send Reply</button>
                    </form>
                    <div id="replyResponseArea"></div>
                </div>

                <div class="section">
                    <h2>/query Endpoint Test</h2>
                    <form id="queryForm">
                        <label for="querySql">SQL Query:</label>
                        <textarea id="querySql" name="query" rows="4" required></textarea>

                        <label for="queryBind">Bind Parameters (JSON Array String, optional):</label>
                        <input type="text" id="queryBind" name="bind" placeholder='["value1", "value2"]'>

                        <label for="queryRawJson">Raw JSON Body (override above):</label>
                        <textarea id="replyRawJson" name="rawJson" rows="4" placeholder='{"query": "SELECT * FROM chat_logs LIMIT 10"}''></textarea>

                        <button type="button" onclick="submitForm('/query', 'queryForm', 'queryResponseArea')">Execute Query</button>
                    </form>
                    <div id="queryResponseArea"></div>
                </div>


                <div class="status-section">
                    <h2>Database Observation Status</h2>
                    <p id="dbStatus">Checking status...</p>
                    <h3>Last Chat Logs</h3>
                    <div id="lastLogs"></div>
                </div>

                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        fetchDbStatus();
                        setInterval(fetchDbStatus, 5000);
                        updateReplyDataPlaceholder(); // Initial placeholder setup
                    });

                    function fetchDbStatus() {
                        fetch('/config/dbstatus')
                            .then(response => response.json())
                            .then(data => {
                                const dbStatusElement = document.getElementById('dbStatus');
                                const lastLogsContainer = document.getElementById('lastLogs');
                                lastLogsContainer.innerHTML = '';

                                if (data.success && data.message) {
                                    if (data.message.isObserving) {
                                        dbStatusElement.textContent = data.message.statusMessage + " ✅";
                                        dbStatusElement.className = 'status-good';
                                    } else {
                                        dbStatusElement.textContent = data.message.statusMessage + " ❌";
                                        dbStatusElement.className = 'status-bad';
                                    }

                                    if (data.message.lastLogs && data.message.lastLogs.length > 0) {
                                        const table = document.createElement('table');
                                        table.className = 'log-table';
                                        const headerRow = table.insertRow();
                                        const headers = ['ID', 'Chat ID', 'User ID', 'Message', 'Created At'];
                                        headers.forEach(headerText => {
                                            const th = document.createElement('th');
                                            th.textContent = headerText;
                                            headerRow.appendChild(th);
                                        });

                                        data.message.lastLogs.forEach(log => {
                                            const row = table.insertRow();
                                            ['_id', 'chat_id', 'user_id', 'message', 'created_at'].forEach(key => {
                                                const cell = row.insertCell();
                                                cell.textContent = log[key] || '';
                                            });
                                        });
                                        lastLogsContainer.appendChild(table);
                                    } else {
                                        lastLogsContainer.textContent = 'No recent logs.';
                                    }


                                } else {
                                    dbStatusElement.textContent = 'Error checking DB status ⚠️';
                                    dbStatusElement.className = 'status-bad';
                                }
                             })
                            .catch(error => {
                                console.error('Error fetching DB status:', error);
                                const dbStatusElement = document.getElementById('dbStatus');
                                dbStatusElement.textContent = 'Error checking DB status ⚠️';
                                dbStatusElement.className = 'status-bad';
                            });
                    }


                    function submitForm(endpoint, formId, responseAreaId, postDataOverride) {
                        const form = document.getElementById(formId);
                        const responseArea = document.getElementById(responseAreaId);
                        if (responseArea) responseArea.textContent = 'Sending request...';

                        let formData = {};
                        if (postDataOverride) { // Use postDataOverride if provided
                            formData = postDataOverride;
                        } else {
                            let rawJsonTextarea = form ? form.querySelector('[name="rawJson"]') : null;
                            let rawJson = rawJsonTextarea ? rawJsonTextarea.value.trim() : '';

                            if (rawJson) {
                                try {
                                    formData = JSON.parse(rawJson);
                                } catch (e) {
                                    if (responseArea) responseArea.textContent = 'Error parsing raw JSON: ' + e.message;
                                    return;
                                }
                            } else if (form) {
                                if (endpoint === '/query') { // Specific handling for /query form
                                    const querySqlElement = document.getElementById('querySql');
                                    const queryBindElement = document.getElementById('queryBind');
                                    formData['query'] = querySqlElement.value;
                                    formData['bind'] = queryBindElement.value;
                                } else { // Existing logic for other forms (like /reply)
                                    const replyTypeElement = form.querySelector('[name="type"]');
                                    const replyDataElement = form.querySelector('[name="data"]');
                                    const messageType = replyTypeElement ? replyTypeElement.value : 'text'; // Default to text if type is not found

                                    if (messageType === 'image_multiple') {
                                        formData['data'] = JSON.parse(replyDataElement.value); // Take the value as string for image_multiple - it's already JSON string
                                    } else {
                                        formData['data'] = replyDataElement.value; // For other types, take value as string as well
                                    }

                                    for (let element of form.elements) {
                                        if (element.name && element.name !== 'rawJson' && element.name !== 'data' && element.name !== 'type') { // Exclude 'data', 'type' and 'rawJson'
                                            formData[element.name] = element.value;
                                        }
                                    }
                                    if (replyTypeElement) { // Explicitly add type from dropdown
                                        formData['type'] = replyTypeElement.value;
                                    }
                                }
                            }
                        }

                        console.log("Sending FormData:", formData); // Add this line for debugging

                        fetch(endpoint, {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json'
                            },
                            body: JSON.stringify(formData)
                        })
                        .then(response => response.json())
                        .then(data => {
                            if (responseArea) responseArea.textContent = JSON.stringify(data, null, 2);
                            if (endpoint.startsWith('/config/')) {
                                if (data.success) {
                                     alert('Config updated successfully: ' + data.message);
                                     location.reload();
                                 } else {
                                     alert('Config update failed: ' + data.error);
                                 }
                            }
                         })
                        .catch(error => {
                            if (responseArea) responseArea.textContent = 'Error: ' + error.message;
                        });
                    }

                   function updateConfig(type, value) {
                        let url = '/config/' + type;
                        let postData = {};
                        if (type === 'endpoint') {
                            postData = { endpoint: value };
                        } else if (type === 'botname') {
                            postData = { botname: value };
                        } else if (type === 'dbrate' || type === 'sendrate') {
                            postData = { rate: value };
                        } else if (type === 'botport') {
                            postData = { port: value };
                        }
                        submitForm(url, null, null, postData);
                    }

                    function updateReplyDataPlaceholder() {
                        const replyType = document.getElementById('replyType');
                        const replyDataTextarea = document.getElementById('replyData');
                        if (replyType.value === 'image') {
                            replyDataTextarea.placeholder = 'Enter base64 encoded image data';
                        } else if (replyType.value === 'image_multiple') {
                            replyDataTextarea.placeholder = 'Enter base64 encoded image data JSON Array';
                        }
                        else {
                            replyDataTextarea.placeholder = 'Enter message text';
                        }
                    }


                </script>
                <div id="configResponseArea" style="display:none;"></div>
            </body>
            </html>
            """;

    public static String getDocument(Configurable config) {
        String html = CONFIG_PAGE_HTML;
        html = html.replace("CURRENT_WEB_ENDPOINT", config.getWebServerEndpoint());
        html = html.replace("CURRENT_BOT_NAME", config.getBotName());
        html = html.replace("CURRENT_DB_RATE", String.valueOf(config.getDbPollingRate()));
        html = html.replace("CURRENT_SEND_RATE", String.valueOf(config.getMessageSendRate()));
        html = html.replace("CURRENT_BOT_PORT", String.valueOf(config.getBotSocketPort()));
        return html;
    }
}