"""Isolated Zhipu MCP probe. Reads .env without printing credentials or using a model.

Default: initialize and list tools only. --query/--read-url explicitly makes one tool call.
No database access, image generation, automatic retries, or image downloads.
"""
import argparse
import json
from pathlib import Path
import ssl
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--query", help="Public query; makes exactly one search tool call")
    parser.add_argument("--service", choices=("search", "reader"), default="search")
    parser.add_argument("--read-url", help="Public image reference page; makes exactly one reader call")
    args = parser.parse_args()
    if args.query is not None and (not args.query.strip() or len(args.query) > 70):
        parser.error("search smoke test accepts 1-70 public characters")
    if args.query and args.service != "search" or args.read_url and args.service != "reader":
        parser.error("query requires search; read-url requires reader")
    if args.read_url:
        address = urllib.parse.urlparse(args.read_url)
        if address.scheme != "https" or address.hostname not in ("commons.wikimedia.org", "en.wikipedia.org", "www.nasa.gov") or address.username:
            parser.error("reader smoke test accepts only public Wikimedia/NASA HTTPS pages")
    root = Path(__file__).absolute().parents[1]
    config = {}
    for line in (root / ".env").read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            name, value = line.split("=", 1)
            config[name] = value
    key_name = None
    for endpoint_name, candidate in [("MODEL_BACKUP_BASE_URL", "MODEL_BACKUP_API_KEY"), ("MODEL_BASE_URL", "MODEL_API_KEY")]:
        if urllib.parse.urlparse(config.get(endpoint_name, "")).hostname == "open.bigmodel.cn" and config.get(candidate):
            key_name = candidate
            break
    if key_name is None:
        raise RuntimeError("No credential configured for open.bigmodel.cn")
    key = config[key_name]
    endpoint = "https://open.bigmodel.cn/api/mcp/" + ("web_reader" if args.service == "reader" else "web_search_prime") + "/mcp"
    headers = {"Authorization": "Bearer " + key, "Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    report = {"endpoint": endpoint, "credentialRef": key_name, "query": args.query, "readUrl": args.read_url,
              "toolCalls": 0, "imageDownloads": 0}

    def request(method, params, request_id=None):
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if request_id is not None:
            message["id"] = request_id
        req = urllib.request.Request(endpoint, data=json.dumps(message).encode("utf-8"), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=45, context=ssl.create_default_context()) as response:
                session = response.headers.get("Mcp-Session-Id")
                if session:
                    headers["Mcp-Session-Id"] = session
                if request_id is None:
                    return {"httpStatus": response.status}
                if "text/event-stream" in response.headers.get("Content-Type", ""):
                    size = 0
                    while size < 262144:
                        line = response.readline(262145)
                        if not line:
                            break
                        size += len(line)
                        if line.startswith(b"data:"):
                            value = json.loads(line[5:])
                            if value.get("id") == request_id:
                                return value
                    raise RuntimeError("No bounded MCP response")
                data = response.read(262145)
                if len(data) > 262144:
                    raise RuntimeError("MCP response exceeds probe limit")
                return json.loads(data)
        except urllib.error.HTTPError as error:
            # Only stable error codes; no body, header, URL or credential echo.
            raise RuntimeError("MCP_HTTP_" + str(error.code)) from None

    exit_code = 0
    try:
        initialized = request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                             "clientInfo": {"name": "novid-isolated-probe", "version": "1"}}, 1)
        if "error" in initialized:
            raise RuntimeError("MCP_INITIALIZE_ERROR")
        report["initialize"] = initialized.get("result", {})
        headers["MCP-Protocol-Version"] = initialized.get("result", {}).get("protocolVersion", "2025-11-25")
        request("notifications/initialized", {})
        directory = request("tools/list", {}, 2)
        if "error" in directory:
            raise RuntimeError("MCP_LIST_ERROR")
        tools = directory.get("result", {}).get("tools", [])
        report["tools"] = [{"name": t.get("name"), "description": t.get("description"),
                            "inputSchema": t.get("inputSchema")} for t in tools]
        if args.query:
            candidates = [t for t in tools if t.get("name") in ("webSearchPrime", "web_search_prime")]
            if len(candidates) != 1:
                raise RuntimeError("Expected search tool not discovered")
            tool = candidates[0]
            fields = tool["inputSchema"].get("properties", {})
            query_field = next((f for f in ("search_query", "query") if f in fields), None)
            if query_field is None:
                raise RuntimeError("Unknown search parameter")
            arguments = {query_field: args.query}
            if "search_engine" in tool["inputSchema"].get("required", []):
                arguments["search_engine"] = "search_pro"
            if "count" in fields:
                arguments["count"] = 3
            if not set(tool["inputSchema"].get("required", [])).issubset(arguments):
                raise RuntimeError("Additional required parameters need review")
            report["toolCalls"] = 1
            result = request("tools/call", {"name": tool["name"], "arguments": arguments}, 3)
            report["searchResponse"] = result
            if "error" in result or result.get("result", {}).get("isError"):
                raise RuntimeError("MCP_SEARCH_FAILED")
        if args.read_url:
            candidates = [t for t in tools if t.get("name") in ("webReader", "web_reader")]
            if len(candidates) != 1:
                raise RuntimeError("Expected reader tool not discovered")
            tool = candidates[0]
            fields = tool["inputSchema"].get("properties", {})
            url_field = next((f for f in ("url", "URL") if f in fields), None)
            if url_field is None:
                raise RuntimeError("Unknown reader parameter")
            arguments = {url_field: args.read_url}
            if "retain_images" in fields:
                arguments["retain_images"] = True
            if "return_format" in fields:
                arguments["return_format"] = "markdown"
            if not set(tool["inputSchema"].get("required", [])).issubset(arguments):
                raise RuntimeError("Additional reader parameters need review")
            report["toolCalls"] = 1
            result = request("tools/call", {"name": tool["name"], "arguments": arguments}, 3)
            report["readerResponse"] = result
            if "error" in result or result.get("result", {}).get("isError"):
                raise RuntimeError("MCP_READER_FAILED")
        report["status"] = "SUCCESS"
    except Exception as error:
        report["status"] = "FAILED"
        report["error"] = str(error) if type(error) is RuntimeError else type(error).__name__
        exit_code = 1
    output = root / "var" / "mcp-zhipu-smoke" / (args.service + "-report.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    safe = json.dumps(report, ensure_ascii=False, indent=2).replace(key, "[REDACTED]")
    output.write_text(safe, encoding="utf-8")
    print(safe)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
