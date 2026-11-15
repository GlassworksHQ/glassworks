// Improv Serial to the kiosk app: the improv-serial package (packages/improv-serial) with the app's
// socket name, and errors that call the device "the panel".
import { ImprovSerialClient as Client, type ByteStream } from 'improv-serial';

export {
  PacketReader, PacketType, Rpc, State, frame, parseResult, rpc, strings, wifiSettings,
  type ByteStream, type DeviceInfo, type Packet, type WifiNetwork,
} from 'improv-serial';

export { IMPROV_SOCKET } from '@glassworks/app-adb';

export class ImprovSerialClient extends Client {
  constructor(io: ByteStream) {
    super(io, { deviceLabel: 'panel' });
  }
}
