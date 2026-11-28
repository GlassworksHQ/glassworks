// Samsung Download mode (the Odin protocol) over WebUSB. A port of Heimdall's protocol (MIT, see ../LICENSE).
export {
  OdinDevice,
  parseDeviceInfo,
  type DeviceInfo,
  type FlashProgress,
  type FlashRequest,
  type OdinOptions,
  type SessionOptions,
  type WritablePartition,
} from './odin.ts';
export { OdinError, type OdinErrorCode } from './errors.ts';
export {
  BinaryType,
  DeviceType,
  PIT_ENTRY_SIZE,
  PIT_HEADER_SIZE,
  PIT_MAGIC,
  PitError,
  blockSize,
  parsePit,
  partitionBytes,
  type Pit,
  type PitEntry,
} from './pit.ts';
export {
  CLAIM_HELP,
  DOWNLOAD_MODE_PRODUCT_IDS,
  ODIN_USB_FILTERS,
  SAMSUNG_VENDOR_ID,
  WebUsbOdinTransport,
  findInterface,
  isDownloadMode,
  type OdinTransport,
} from './transport.ts';
export { FAST_TRANSFER, LEGACY_TRANSFER, type TransferShape } from './protocol.ts';
