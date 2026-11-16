"""Home Assistant's side of EspHomeBluetoothConnectionsTest: aioesphomeapi, the version Home Assistant
pins, makes a GATT connection through the proxy as bleak-esphome does (client.py): subscribe to raw
advertisements and to the free slots (connect.py connect_scanner), connect v3 without a cache, get the
services, read, write, turn on notifications and write the CCCD (REMOTE_CACHING), read a descriptor,
disconnect, then connect again with a cache and disconnect.
It connects with the device's Noise key: the proxy only answers an authenticated connection.
Usage: esphome_bluetooth_connections.py <port> <noise psk, base64> <address>; prints RESULT {json}."""
import asyncio
import json
import sys

from aioesphomeapi import APIClient
from aioesphomeapi.model import BluetoothProxyFeature

CCCD = "00002902-0000-1000-8000-00805f9b34fb"


async def main(port: int, psk: str, address: int) -> None:
    cli = APIClient("127.0.0.1", port, None, client_info="Home Assistant test", noise_psk=psk)
    await cli.connect(login=True)
    info = await cli.device_info()
    flags = info.bluetooth_proxy_feature_flags_compat(cli.api_version)
    out = {
        "connectable": bool(flags & BluetoothProxyFeature.ACTIVE_CONNECTIONS),
        "remote_caching": bool(flags & BluetoothProxyFeature.REMOTE_CACHING),
        "pairing": bool(flags & BluetoothProxyFeature.PAIRING),
        "cache_clearing": bool(flags & BluetoothProxyFeature.CACHE_CLEARING),
        "free": [],
        "states": [],
    }
    cli.subscribe_bluetooth_le_raw_advertisements(lambda resp: None)
    cli.subscribe_bluetooth_connections_free(
        lambda free, limit, allocated: out["free"].append([free, limit, len(allocated), address in allocated])
    )

    def on_state(connected: bool, mtu: int, error: int) -> None:
        out["states"].append([connected, mtu, error])

    unsub = await cli.bluetooth_device_connect(address, on_state, timeout=10, feature_flags=flags, has_cache=False, address_type=0)
    services = (await cli.bluetooth_gatt_get_services(address)).services
    out["services"] = [
        {
            "uuid": s.uuid,
            "handle": s.handle,
            "characteristics": [
                {
                    "uuid": c.uuid,
                    "handle": c.handle,
                    "properties": c.properties,
                    "descriptors": [{"uuid": d.uuid, "handle": d.handle} for d in c.descriptors],
                }
                for c in s.characteristics
            ],
        }
        for s in services
    ]
    char = services[0].characteristics[0]
    out["read"] = (await cli.bluetooth_gatt_read(address, char.handle)).hex()
    await cli.bluetooth_gatt_write(address, char.handle, b"\x2a", True)
    out["read_after_write"] = (await cli.bluetooth_gatt_read(address, char.handle)).hex()

    got = asyncio.get_running_loop().create_future()

    def on_notify(handle: int, data: bytearray) -> None:
        if not got.done():
            got.set_result([handle, data.hex()])

    await cli.bluetooth_gatt_start_notify(address, char.handle, on_notify)
    cccd = next(d for d in char.descriptors if d.uuid == CCCD)
    await cli.bluetooth_gatt_write_descriptor(address, cccd.handle, b"\x01\x00")
    out["notification"] = await asyncio.wait_for(got, 10)
    out["cccd"] = (await cli.bluetooth_gatt_read_descriptor(address, cccd.handle)).hex()
    await cli.bluetooth_device_disconnect(address)
    unsub()

    unsub = await cli.bluetooth_device_connect(address, on_state, timeout=10, feature_flags=flags, has_cache=True, address_type=0)
    await cli.bluetooth_device_disconnect(address)
    unsub()
    await cli.disconnect()
    print("RESULT " + json.dumps(out), flush=True)


asyncio.run(main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3])))
