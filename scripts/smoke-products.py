#!/usr/bin/env python3
"""Functional checks against a running synthetic demo. Uses Python standard library only."""
import json
import os
import urllib.parse
import urllib.request

BASE = os.environ.get("LIFESYNC_SMOKE_URL", "http://127.0.0.1:8080").rstrip("/")


def rpc(method, params):
    body = json.dumps(dict(jsonrpc="2.0", id=1, method=method, params=params)).encode()
    request = urllib.request.Request(BASE + "/mcp", data=body, headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": "2025-03-26",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    assert "error" not in result, result
    return result["result"]


def graph(customer, product=None):
    args = {"customerId": customer}
    if product:
        args["productId"] = product
    result = rpc("tools/call", {"name": "get_customer_subgraph", "arguments": args})
    assert not result["isError"], result
    return json.loads(result["content"][0]["text"])


assert rpc("initialize", {
    "protocolVersion": "2025-03-26", "capabilities": {},
    "clientInfo": {"name": "lifesync-smoke", "version": "0.9.0"},
})["protocolVersion"] == "2025-03-26"
notification = urllib.request.Request(BASE + "/mcp", data=json.dumps({
    "jsonrpc": "2.0", "method": "notifications/initialized",
}).encode(), headers={"Content-Type": "application/json"})
with urllib.request.urlopen(notification, timeout=10) as response:
    assert response.status == 202
assert rpc("tools/list", {})["tools"][0]["name"] == "get_customer_subgraph"

for index in range(1, 4):
    customer = f"C{index:03d}"
    result = graph(customer)
    assert result["customerId"] == customer
    assert len(result["goals"]) == 4
    assert all(g["goalId"].startswith(f"PG-{index:03d}-") for g in result["goals"])
    for goal in result["goals"]:
        providers = goal["providers"]
        if goal["productId"] == "PLCC" and index != 1:
            assert not providers and goal["status"] == "PLCC_ACCOUNT_RESTRICTED"
            continue
        assert len(providers) == 3, (customer, goal)
        distances = [p["distanceMiles"] for p in providers]
        assert distances == sorted(distances)
        for provider in providers:
            offers = provider["offers"]
            assert offers
            assert all(o["productId"] == goal["productId"]
                       and o["merchantId"] == provider["merchantId"] for o in offers)
            assert all(o["customerId"] in (None, customer) for o in offers)
            assert all(o["offerId"] != "PO-EXPIRED" for o in offers)
            values = [-o["discountPercent"] if goal["productId"] == "PLCC"
                      else (o["apr"], o["fees"]) for o in offers]
            assert values == sorted(values)
    loan = graph(customer, "AUTO_LOAN")
    assert len(loan["goals"]) == 1 and loan["goals"][0]["productId"] == "AUTO_LOAN"
    query = urllib.parse.urlencode({"customerId": customer})
    with urllib.request.urlopen(BASE + "/experience?" + query, timeout=90) as response:
        html = response.read().decode()
        assert response.status == 200
        assert customer in html
        assert 'role="alert"' not in html, "UI returned an error"
        for goal in result["goals"]:
            assert goal["description"] in html, "UI omitted a customer goal"
            for provider in goal["providers"]:
                assert provider["name"] in html, "UI omitted a provider"
        if index != 1:
            assert "Home Furnishings" not in html, "Restricted PLCC merchant leaked"
    print(customer, "customer scope, products, account gates, distance, offers and UI passed")

assert rpc("tools/call", {"name": "get_customer_subgraph",
                         "arguments": {"customerId": "MISSING"}})["isError"]
print("MCP discovery, calls, missing customer and all functional checks passed")
