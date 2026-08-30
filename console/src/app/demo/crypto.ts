/**
 * SHA-256 and HMAC-SHA256, synchronously.
 *
 * The Web Crypto API provides both, but only as promises. Generation walks
 * tens of thousands of cells and masks a value inside that loop, so an async
 * hash would turn a tight synchronous pass into a promise per cell. This is a
 * compact synchronous implementation of the same primitives.
 *
 * It exists to mirror the service's masking, which derives every masked value
 * from HMAC-SHA256 over the dataset salt, the column and the value. The
 * properties that matters are the same ones: stable, irreversible without the
 * key, and unlinkable across datasets.
 */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const INITIAL = new Uint32Array([
  0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);

function rotr(value: number, bits: number): number {
  return (value >>> bits) | (value << (32 - bits));
}

/** SHA-256 over raw bytes. */
export function sha256(input: Uint8Array<ArrayBufferLike>): Uint8Array<ArrayBuffer> {
  // Message schedule and working variables are reused across blocks: this runs
  // once per masked value, so the allocation would otherwise dominate.
  const words = new Uint32Array(64);
  const hash = INITIAL.slice();

  const bitLength = input.length * 8;
  const paddedLength = (((input.length + 8) >> 6) + 1) << 6;
  const padded = new Uint8Array(paddedLength);
  padded.set(input);
  padded[input.length] = 0x80;

  // Length is a 64-bit big-endian count of bits. Only the low 32 bits are
  // written: nothing here hashes more than 512 MB.
  const view = new DataView(padded.buffer);
  view.setUint32(paddedLength - 4, bitLength >>> 0, false);
  view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000), false);

  for (let offset = 0; offset < paddedLength; offset += 64) {
    for (let i = 0; i < 16; i++) {
      words[i] = view.getUint32(offset + i * 4, false);
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(words[i - 15], 7) ^ rotr(words[i - 15], 18) ^ (words[i - 15] >>> 3);
      const s1 = rotr(words[i - 2], 17) ^ rotr(words[i - 2], 19) ^ (words[i - 2] >>> 10);
      words[i] = (words[i - 16] + s0 + words[i - 7] + s1) | 0;
    }

    let [a, b, c, d, e, f, g, h] = hash;

    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + S1 + ch + K[i] + words[i]) | 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (S0 + maj) | 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) | 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) | 0;
    }

    hash[0] = (hash[0] + a) | 0;
    hash[1] = (hash[1] + b) | 0;
    hash[2] = (hash[2] + c) | 0;
    hash[3] = (hash[3] + d) | 0;
    hash[4] = (hash[4] + e) | 0;
    hash[5] = (hash[5] + f) | 0;
    hash[6] = (hash[6] + g) | 0;
    hash[7] = (hash[7] + h) | 0;
  }

  const digest = new Uint8Array(32);
  const digestView = new DataView(digest.buffer);
  for (let i = 0; i < 8; i++) {
    digestView.setUint32(i * 4, hash[i] >>> 0, false);
  }
  return digest;
}

const encoder = new TextEncoder();

/** HMAC-SHA256 with a string key over string parts. */
export class Hmac {
  private readonly innerPad: Uint8Array;
  private readonly outerPad: Uint8Array;

  constructor(key: string) {
    let keyBytes: Uint8Array<ArrayBufferLike> = encoder.encode(key);
    if (keyBytes.length > 64) {
      keyBytes = sha256(keyBytes);
    }

    this.innerPad = new Uint8Array(64);
    this.outerPad = new Uint8Array(64);
    this.innerPad.set(keyBytes);
    this.outerPad.set(keyBytes);

    for (let i = 0; i < 64; i++) {
      this.innerPad[i] ^= 0x36;
      this.outerPad[i] ^= 0x5c;
    }
  }

  /**
   * Digest over the parts, each length-prefixed so boundaries cannot be forged:
   * without it the pairs ("ab", "c") and ("a", "bc") would collide.
   */
  digest(...parts: string[]): Uint8Array<ArrayBuffer> {
    const encoded = parts.map((part) => encoder.encode(part ?? ' null'));
    const total = encoded.reduce((sum, part) => sum + part.length + 4, 0);

    const message = new Uint8Array(64 + total);
    message.set(this.innerPad);
    let offset = 64;
    for (const part of encoded) {
      new DataView(message.buffer).setUint32(offset, part.length, false);
      offset += 4;
      message.set(part, offset);
      offset += part.length;
    }

    const inner = sha256(message);
    const outer = new Uint8Array(96);
    outer.set(this.outerPad);
    outer.set(inner, 64);
    return sha256(outer);
  }

  /** A non-negative integer derived from the digest. */
  asInt(bound: number, ...parts: string[]): number {
    const digest = this.digest(...parts);
    // 48 bits, comfortably inside the exact-integer range.
    const value =
      digest[0] * 2 ** 40 +
      digest[1] * 2 ** 32 +
      digest[2] * 2 ** 24 +
      (digest[3] << 16) +
      (digest[4] << 8) +
      digest[5];
    return bound <= 0 ? 0 : value % bound;
  }

  /** A double in the half-open unit interval. */
  asUnit(...parts: string[]): number {
    return this.asInt(1 << 30, ...parts) / (1 << 30);
  }

  /** Lowercase hex, truncated to the requested length. */
  asHex(length: number, ...parts: string[]): string {
    const digest = this.digest(...parts);
    let hex = '';
    for (const byte of digest) {
      hex += byte.toString(16).padStart(2, '0');
    }
    return hex.slice(0, length);
  }

  /** A deterministic digit stream, re-derived as it is exhausted. */
  asDigits(count: number, ...parts: string[]): number[] {
    const digits: number[] = [];
    let round = 0;
    let digest = this.digest(...parts);

    for (let i = 0; i < count; i++) {
      const index = i % digest.length;
      if (i > 0 && index === 0) {
        round++;
        digest = this.digest(...parts, `round${round}`);
      }
      digits.push(digest[index] % 10);
    }
    return digits;
  }
}
