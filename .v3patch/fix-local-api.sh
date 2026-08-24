#!/usr/bin/env bash
set -euo pipefail
F="chatgpt-local-api/app/src/main/java/com/example/chatgptlocalapi/LocalApiService.java"
python3 - "$F" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
repls = {
    'private void sendChatJson(OutputStream out, String id, long now, AgentProtocol.ParsedReply reply) throws IOException {': 'private void sendChatJson(OutputStream out, String id, long now, AgentProtocol.ParsedReply reply) throws Exception {',
    'private JSONArray toChatToolCalls(List<AgentProtocol.ToolCall> calls) {': 'private JSONArray toChatToolCalls(List<AgentProtocol.ToolCall> calls) throws Exception {',
    'private void sendChatStream(OutputStream out, String id, long created, AgentProtocol.ParsedReply reply) throws IOException {': 'private void sendChatStream(OutputStream out, String id, long created, AgentProtocol.ParsedReply reply) throws Exception {',
    'private JSONObject roleDelta() {': 'private JSONObject roleDelta() throws Exception {',
    'private JSONObject contentDelta(String s) {': 'private JSONObject contentDelta(String s) throws Exception {',
    'private String chatChunk(String id, long created, JSONObject delta, String finish) {': 'private String chatChunk(String id, long created, JSONObject delta, String finish) throws Exception {',
    'private JSONObject buildResponseObject(AgentProtocol.ParsedReply reply) {': 'private JSONObject buildResponseObject(AgentProtocol.ParsedReply reply) throws Exception {',
    'private void sendResponsesStream(OutputStream out, JSONObject response, AgentProtocol.ParsedReply reply) throws IOException {': 'private void sendResponsesStream(OutputStream out, JSONObject response, AgentProtocol.ParsedReply reply) throws Exception {',
}
for old, new in repls.items():
    if old not in s:
        raise SystemExit(f'missing expected signature: {old}')
    s = s.replace(old, new, 1)
old = 'private String errorJson(String message) { JSONObject e=new JSONObject();e.put("message",message);e.put("type","chatgpt_web_agent_error");JSONObject r=new JSONObject();r.put("error",e);return r.toString(); }'
new = '''private String errorJson(String message) {
        try {
            JSONObject e = new JSONObject();
            e.put("message", message == null ? "Unknown error" : message);
            e.put("type", "chatgpt_web_agent_error");
            JSONObject r = new JSONObject();
            r.put("error", e);
            return r.toString();
        } catch (Exception ignored) {
            String safe = message == null ? "Unknown error" : message.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\\\"").replace("\\n", "\\\\n").replace("\\r", "\\\\r");
            return "{\\\"error\\\":{\\\"message\\\":\\\"" + safe + "\\\",\\\"type\\\":\\\"chatgpt_web_agent_error\\\"}}";
        }
    }'''
if old not in s:
    raise SystemExit('missing errorJson one-line implementation')
s = s.replace(old, new, 1)
p.write_text(s)
PY
