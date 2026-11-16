"""Home Assistant's side of EspHomeCapsTimeTest (#337): aioesphomeapi, the version Home Assistant
pins, over Noise with the test key.

1. Reads the device's capabilities as HA's ESPHome integration does (device_capabilities_compat).
2. Answers the time as the client does today (the timezone string and parsed_timezone), then once
   per IANA zone with parsed_timezone alone, the shape after ESPHome's deprecation of the string.
3. Prints "SENT", reads back one JSON line: the rules the server handed to onTimeZone, in order.
4. Prints RESULT {json}: the capabilities, and every rule the server rebuilt that aioesphomeapi's
   own parser reads differently from the zone it was built from.

Usage: esphome_caps_time.py <port> <base64 key>. Touches nothing but the test server."""
import asyncio
import json
import sys
import zoneinfo

from aioesphomeapi import APIClient
from aioesphomeapi.api_pb2 import GetTimeResponse
from aioesphomeapi.connection import _build_parsed_tz_proto
from aioesphomeapi.posix_tz import parse_posix_tz
from aioesphomeapi.timezone import iana_to_posix_tz


def answer(rule: str, with_string: bool) -> GetTimeResponse:
    resp = GetTimeResponse(epoch_seconds=1_800_000_000)
    if with_string:
        resp.timezone = rule
    resp.parsed_timezone.CopyFrom(_build_parsed_tz_proto(rule))
    return resp


def same(sent: str, rebuilt: str) -> bool:
    try:
        return parse_posix_tz(rebuilt) == parse_posix_tz(sent)
    except ValueError:
        return False


async def main(port: int, key: str) -> None:
    cli = APIClient("127.0.0.1", port, None, noise_psk=key, client_info="Home Assistant test")
    await cli.connect(login=True)
    info = await cli.device_info()
    caps = await cli.device_capabilities_compat(info)
    api_version = f"{cli.api_version.major}.{cli.api_version.minor}"

    # Only the zones the client would send: a system zone that tzdata lacks has no rule.
    sendable = [(z, r) for z in sorted(zoneinfo.available_timezones())
                if (r := iana_to_posix_tz(z)) and _build_parsed_tz_proto(r) is not None]
    zones = [z for z, _ in sendable]
    rules = [r for _, r in sendable]
    conn = cli._get_connection()
    conn.send_message(answer(rules[0], with_string=True))
    for rule in rules:
        conn.send_message(answer(rule, with_string=False))
    await cli.device_info()  # the server answers it only after the time answers sent before it
    print("SENT", flush=True)
    got = json.loads(sys.stdin.readline())
    await cli.disconnect()

    mismatches = [
        {"zone": z, "sent": r, "rebuilt": g}
        for z, r, g in zip(zones, rules, got[1:])
        if not same(r, g)
    ]
    print("RESULT " + json.dumps({
        "api_version": api_version,
        "bluetooth_proxy_feature_flags": caps.bluetooth_proxy.feature_flags,
        "zones": len(zones),
        "first": got[0],
        "first_sent": rules[0],
        "rebuilt": len(got) - 1,
        "mismatches": mismatches[:10],
        "mismatch_count": len(mismatches),
    }), flush=True)


asyncio.run(main(int(sys.argv[1]), sys.argv[2]))
