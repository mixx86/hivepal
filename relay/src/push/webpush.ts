/**
 * Hivepal: Web Push message encryption (RFC 8291, "aes128gcm" content coding of RFC 8188), so a
 * UnifiedPush distributor's server only ever sees ciphertext. The app's keys: `p256dh` (its P-256
 * public key, uncompressed) and `auth` (a 16-byte secret), both base64url.
 */
import { createCipheriv, createECDH, hkdfSync, randomBytes } from "node:crypto";

const RECORD_SIZE = 4096;

export interface WebPushKeys {
  p256dh: string;
  auth: string;
}

/** The encrypted body for `plaintext` (one record): salt ‖ rs ‖ idlen ‖ keyid ‖ ciphertext. */
export function encrypt(plaintext: Buffer, keys: WebPushKeys, salt = randomBytes(16), ephemeral = createECDH("prime256v1")): Buffer {
  const uaPublic = Buffer.from(keys.p256dh, "base64url");
  const authSecret = Buffer.from(keys.auth, "base64url");
  if (uaPublic.length !== 65 || uaPublic[0] !== 0x04) throw new Error("p256dh must be an uncompressed P-256 point");
  if (authSecret.length !== 16) throw new Error("auth must be 16 bytes");
  if (plaintext.length + 1 + 16 > RECORD_SIZE - 86) throw new Error("payload too large for one record");

  try {
    ephemeral.getPublicKey();
  } catch {
    ephemeral.generateKeys(); // a fresh key pair per message
  }
  const asPublic = ephemeral.getPublicKey();
  const ecdhSecret = ephemeral.computeSecret(uaPublic);
  // RFC 8291 §3.4: IKM from the ECDH secret, keyed by the auth secret and both public keys.
  const keyInfo = Buffer.concat([Buffer.from("WebPush: info\0"), uaPublic, asPublic]);
  const ikm = Buffer.from(hkdfSync("sha256", ecdhSecret, authSecret, keyInfo, 32));
  // RFC 8188 §2.2-2.3: content key and nonce from the salt.
  const cek = Buffer.from(hkdfSync("sha256", ikm, salt, Buffer.from("Content-Encoding: aes128gcm\0"), 16));
  const nonce = Buffer.from(hkdfSync("sha256", ikm, salt, Buffer.from("Content-Encoding: nonce\0"), 12));

  const cipher = createCipheriv("aes-128-gcm", cek, nonce);
  // The last (and only) record ends with the 0x02 padding delimiter.
  const body = Buffer.concat([cipher.update(Buffer.concat([plaintext, Buffer.from([0x02])])), cipher.final(), cipher.getAuthTag()]);
  const header = Buffer.alloc(21);
  salt.copy(header, 0);
  header.writeUInt32BE(RECORD_SIZE, 16);
  header.writeUInt8(asPublic.length, 20);
  return Buffer.concat([header, asPublic, body]);
}
