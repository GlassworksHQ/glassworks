/** What went wrong, for code to act on. The message says it in words. */
export type OdinErrorCode =
  /** Not a Samsung device in Download mode, or no Download mode interface on it. */
  | 'not-download-mode'
  /** The browser couldn't open or claim the interface ({@link CLAIM_HELP} says what to do). */
  | 'claim-failed'
  /** A USB transfer failed. */
  | 'usb'
  /** A USB transfer didn't finish in time. */
  | 'timeout'
  /** The device didn't answer ODIN with LOKE. */
  | 'handshake'
  /** The device answered with something the protocol doesn't expect. */
  | 'protocol'
  /** The device answered with its failure status: {@link OdinError.status} holds its code. */
  | 'device'
  /** This library refused to write, before anything was sent ({@link OdinDevice.flash}'s checks). */
  | 'refused'
  /**
   * A call out of order (a PIT before the session, a flash after it ended), one made while another runs, or any
   * call after a session setup that failed part way.
   */
  | 'state';

export class OdinError extends Error {
  override name = 'OdinError';
  constructor(
    readonly code: OdinErrorCode,
    message: string,
    options?: ErrorOptions & { status?: number },
  ) {
    super(message, options);
    this.status = options?.status;
  }
  /** For `device`: the signed status the device sent with its failure response. */
  readonly status?: number;
}
