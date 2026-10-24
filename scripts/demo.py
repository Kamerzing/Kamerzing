#!/usr/bin/env python3
"""Run the MVP acceptance flow against an explicitly selected instance."""

import argparse
import json
import os
import secrets
import ssl
import sys
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="For example https://payments.example.com")
    parser.add_argument("--insecure-local", action="store_true", help="Trust localhost's test certificate")
    args = parser.parse_args()
    email = os.environ.get("PAYMENT_ADMIN_EMAIL")
    password = os.environ.get("PAYMENT_ADMIN_PASSWORD")
    if not email or not password:
        parser.error("PAYMENT_ADMIN_EMAIL and PAYMENT_ADMIN_PASSWORD must be set")
    parsed = urlparse(args.base_url)
    if parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1"}:
        parser.error("A non-local target requires HTTPS")
    if args.insecure_local and parsed.hostname not in {"localhost", "127.0.0.1"}:
        parser.error("--insecure-local is only allowed for localhost")
    ssl_context = ssl._create_unverified_context() if args.insecure_local else ssl.create_default_context()
    base = args.base_url.rstrip("/")

    def call(method, path, body=None, token=None, key=None, expected=200):
        headers = {}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=25, context=ssl_context) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        result = json.loads(raw) if raw else None
        if status != expected:
            code = result.get("code", "") if isinstance(result, dict) else ""
            raise AssertionError(f"{method} {path}: expected HTTP {expected}, got {status} {code}")
        return result

    admin_token = call("POST", "/api/v1/auth/login", {"email": email, "password": password})["accessToken"]

    def new_user():
        user_email = f"demo-{uuid.uuid4()}@example.test"
        user_password = secrets.token_urlsafe(24)
        call("POST", "/api/v1/auth/register", {"email": user_email, "password": user_password}, expected=201)
        token = call("POST", "/api/v1/auth/login", {"email": user_email, "password": user_password})["accessToken"]
        wallet = call("POST", "/api/v1/wallets", {"currency": "RUB"}, token, expected=201)
        return token, wallet["id"]

    sender_token, sender_wallet = new_user()
    receiver_token, receiver_wallet = new_user()
    top_up = call("POST", "/api/v1/admin/top-ups",
                  {"walletId": sender_wallet, "amountMinor": 100000, "currency": "RUB"},
                  admin_token, str(uuid.uuid4()), 201)
    transfer_key = str(uuid.uuid4())
    transfer_body = {"fromWalletId": sender_wallet, "toWalletId": receiver_wallet,
                     "amountMinor": 25000, "currency": "RUB"}
    transfer = call("POST", "/api/v1/payments/transfers", transfer_body,
                    sender_token, transfer_key, 201)
    replay = call("POST", "/api/v1/payments/transfers", transfer_body,
                  sender_token, transfer_key, 201)
    assert replay == transfer, "Transfer replay changed its saved result"
    call("GET", f"/api/v1/wallets/{receiver_wallet}/transactions", token=sender_token, expected=404)
    call("POST", f"/api/v1/payments/{transfer['id']}/refund", {}, sender_token,
         str(uuid.uuid4()), 404)
    refund_key = str(uuid.uuid4())
    refund = call("POST", f"/api/v1/payments/{transfer['id']}/refund", {},
                  receiver_token, refund_key, 201)
    assert refund["originalPaymentId"] == transfer["id"]
    assert call("POST", f"/api/v1/payments/{transfer['id']}/refund", {},
                receiver_token, refund_key, 201) == refund
    sender_balance = call("GET", f"/api/v1/wallets/{sender_wallet}", token=sender_token)["balanceMinor"]
    receiver_balance = call("GET", f"/api/v1/wallets/{receiver_wallet}", token=receiver_token)["balanceMinor"]
    assert (sender_balance, receiver_balance) == (100000, 0)
    sender_history = call("GET", f"/api/v1/wallets/{sender_wallet}/transactions?size=10", token=sender_token)
    assert {item["paymentId"] for item in sender_history["items"]} == {top_up["id"], transfer["id"], refund["id"]}
    report = call("GET", "/api/v1/admin/reconciliation", token=admin_token)
    assert report["consistent"] is True and report["discrepancies"] == []
    print(json.dumps({"topUpId": top_up["id"], "transferId": transfer["id"],
                      "refundId": refund["id"], "senderBalanceMinor": sender_balance,
                      "receiverBalanceMinor": receiver_balance, "reconciliationConsistent": True},
                     ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, urllib.error.URLError, ValueError, KeyError) as error:
        print(f"Demo failed: {error}", file=sys.stderr)
        sys.exit(1)
