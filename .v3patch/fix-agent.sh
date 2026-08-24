#!/usr/bin/env bash
set -euo pipefail
F="chatgpt-local-api/app/src/main/java/com/example/chatgptlocalapi/AgentProtocol.java"
python3 - "$F" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
repls = {
    'public static String buildChatPrompt(JSONObject req) {': 'public static String buildChatPrompt(JSONObject req) throws Exception {',
    'public static String buildResponsesPrompt(JSONObject req) {': 'public static String buildResponsesPrompt(JSONObject req) throws Exception {',
    'private static JSONArray normalizeTools(JSONObject req) {': 'private static JSONArray normalizeTools(JSONObject req) throws Exception {',
    'private static JSONArray normalizeResponseTools(JSONArray tools) {': 'private static JSONArray normalizeResponseTools(JSONArray tools) throws Exception {',
    'public static JSONArray normalizeResponsesInput(Object input) {': 'public static JSONArray normalizeResponsesInput(Object input) throws Exception {',
}
for old, new in repls.items():
    if old not in s:
        raise SystemExit(f'missing expected signature: {old}')
    s = s.replace(old, new, 1)
p.write_text(s)
PY
