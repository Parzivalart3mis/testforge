import { MaskStrategy } from '../core/api.types';
import { Hmac } from './crypto';
import { CORPORA, ibanBbanLength, pick } from './corpora';

/**
 * Deterministic masking, ported from the service.
 *
 * Every strategy preserves the properties the schema and the application depend
 * on - length, character class, punctuation, checksum validity, uniqueness -
 * while destroying the link to the original. Replacing sensitive values with a
 * constant would be trivially safe and completely useless: joins break,
 * uniqueness collides, and validation rejects the rows.
 */

export interface MaskContext {
  datasetSalt: string;
  column?: string;
  rowKey?: string;
}

/** Luhn check digit, so masked card numbers still validate. */
export function luhnCheckDigit(payload: number[]): number {
  let sum = 0;
  let doubling = true;
  for (let i = payload.length - 1; i >= 0; i--) {
    let digit = payload[i];
    if (doubling) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9;
      }
    }
    sum += digit;
    doubling = !doubling;
  }
  return (10 - (sum % 10)) % 10;
}

export function isLuhnValid(value: string): boolean {
  const digits = value.replace(/[^0-9]/g, '');
  if (digits.length < 2) {
    return false;
  }
  const payload = digits.slice(0, -1).split('').map(Number);
  return luhnCheckDigit(payload) === Number(digits.slice(-1));
}

/** ISO 7064 MOD 97-10 check digits, so masked IBANs still validate. */
export function ibanCheckDigits(country: string, bban: string): string {
  const rearranged = bban + country + '00';
  let remainder = 0;
  for (const char of rearranged.toUpperCase()) {
    const value = /[0-9]/.test(char) ? char : String(char.charCodeAt(0) - 55);
    for (const digit of value) {
      remainder = (remainder * 10 + Number(digit)) % 97;
    }
  }
  return String(98 - remainder).padStart(2, '0');
}

const IPV4 = /^(\d{1,3}\.){3}\d{1,3}(\/\d{1,2})?$/;
const MAC = /^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$/;

export class MaskingEngine {
  private readonly hmac: Hmac;

  constructor(key: string, private readonly redactionToken = '[REDACTED]') {
    this.hmac = new Hmac(key);
  }

  mask(strategy: MaskStrategy, value: unknown, context: MaskContext): unknown {
    if (strategy === 'PRESERVE' || value === null || value === undefined) {
      return value;
    }
    if (strategy === 'NULLIFY') {
      return null;
    }
    if (strategy === 'DATE_SHIFT') {
      return this.shiftDate(value, context);
    }
    if (strategy === 'NUMERIC_JITTER') {
      return this.jitter(value, context);
    }
    return this.maskText(strategy, String(value), context);
  }

  maskText(strategy: MaskStrategy, value: string, context: MaskContext): string {
    const parts = [context.datasetSalt, context.column ?? '', value];

    switch (strategy) {
      case 'PRESERVE':
        return value;
      case 'REDACT':
        return this.redactionToken;
      case 'HASH':
        return this.hmac.asHex(32, ...parts);
      case 'TOKENIZE':
        return 'tok_' + this.hmac.asHex(16, ...parts);
      case 'PARTIAL':
        return this.partial(value, parts);
      case 'EMAIL':
        return this.email(parts);
      case 'PHONE':
        return this.formatPreservingDigits(value, parts);
      case 'NAME':
        return this.name(value, parts);
      case 'SSN':
        return this.ssn(value, parts);
      case 'CREDIT_CARD':
        return this.creditCard(value, parts);
      case 'IBAN':
        return this.iban(value, parts);
      default:
        return this.hmac.asHex(32, ...parts);
    }
  }

  /**
   * Keeps the edges, replaces the middle. Network addresses route to a
   * format-aware masker first: substituting digits per character in
   * 198.51.100.42 happily produces 138.21.339.32, which inet rejects.
   */
  private partial(value: string, parts: string[]): string {
    const network = this.maskNetworkAddress(value, parts);
    if (network !== null) {
      return network;
    }
    if (value.length <= 2) {
      return this.derivedFiller(value, parts);
    }
    return value[0] + this.derivedFiller(value.slice(1, -1), parts) + value.slice(-1);
  }

  /**
   * A valid address in a reserved range, so masked traffic goes nowhere:
   * TEST-NET for IPv4, 2001:db8::/32 for IPv6, a locally-administered MAC.
   */
  private maskNetworkAddress(value: string, parts: string[]): string | null {
    const trimmed = value.trim();

    if (IPV4.test(trimmed)) {
      const blocks = ['192.0.2', '198.51.100', '203.0.113'];
      const block = blocks[this.hmac.asInt(blocks.length, ...parts, 'block')];
      return `${block}.${1 + this.hmac.asInt(254, ...parts, 'host')}`;
    }
    if (MAC.test(trimmed)) {
      const digits = this.hmac.asDigits(10, ...parts, 'mac');
      const pairs = [];
      for (let i = 0; i < 10; i += 2) {
        pairs.push(`${digits[i].toString(16)}${digits[i + 1].toString(16)}`);
      }
      return `02:${pairs.join(':')}`;
    }
    if (trimmed.includes(':') && /^[0-9a-fA-F:]+(\/\d{1,3})?$/.test(trimmed)) {
      return `2001:db8:${this.hmac.asHex(4, ...parts, 'v6a')}:${this.hmac.asHex(4, ...parts, 'v6b')}::${this.hmac.asHex(4, ...parts, 'v6c')}`;
    }
    return null;
  }

  /** Same length, same character classes. */
  private derivedFiller(original: string, parts: string[]): string {
    const digits = this.hmac.asDigits(original.length, ...parts);
    let filler = '';
    for (let i = 0; i < original.length; i++) {
      const char = original[i];
      if (/[0-9]/.test(char)) {
        filler += String(digits[i]);
      } else if (/[A-Z]/.test(char)) {
        filler += String.fromCharCode(65 + Math.floor((digits[i] * 26) / 10));
      } else if (/[a-z]/.test(char)) {
        filler += String.fromCharCode(97 + Math.floor((digits[i] * 26) / 10));
      } else {
        filler += char;
      }
    }
    return filler;
  }

  /**
   * A valid address at an RFC 2606 domain. The original domain is replaced,
   * not preserved: in a business dataset the domain is often the most
   * identifying part of the record.
   */
  private email(parts: string[]): string {
    const given = pick(CORPORA.givenNames, this.hmac.asInt(1 << 20, ...parts, 'given')).toLowerCase();
    const family = pick(CORPORA.familyNames, this.hmac.asInt(1 << 20, ...parts, 'family')).toLowerCase();
    const domain = pick(CORPORA.emailDomains, this.hmac.asInt(1 << 20, ...parts, 'domain'));
    const discriminator = this.hmac.asInt(100000, ...parts, 'discriminator');
    return `${given}.${family}${discriminator}@${domain}`;
  }

  /** Replaces the digits, keeps the punctuation and the length. */
  private formatPreservingDigits(value: string, parts: string[]): string {
    const digitCount = (value.match(/\d/g) ?? []).length;
    if (digitCount === 0) {
      return value;
    }
    const digits = this.hmac.asDigits(digitCount, ...parts);
    let index = 0;
    return value.replace(/\d/g, () => {
      let digit = digits[index];
      if (index === 0 && digit === 0) {
        digit = 5;
      }
      index++;
      return String(digit);
    });
  }

  private name(value: string, parts: string[]): string {
    const given = pick(CORPORA.givenNames, this.hmac.asInt(1 << 20, ...parts, 'given'));
    const family = pick(CORPORA.familyNames, this.hmac.asInt(1 << 20, ...parts, 'family'));
    return value.trim().includes(' ') ? `${given} ${family}` : given;
  }

  /** The 900-999 area is reserved and never issued, so this cannot be a real number. */
  private ssn(value: string, parts: string[]): string {
    const area = 900 + this.hmac.asInt(100, ...parts, 'area');
    const group = 1 + this.hmac.asInt(99, ...parts, 'group');
    const serial = 1 + this.hmac.asInt(9999, ...parts, 'serial');
    const formatted = `${area}-${String(group).padStart(2, '0')}-${String(serial).padStart(4, '0')}`;
    return value.includes('-') ? formatted : formatted.replace(/-/g, '');
  }

  private creditCard(value: string, parts: string[]): string {
    const digitsOnly = value.replace(/[^0-9]/g, '');
    if (digitsOnly.length < 4) {
      return this.derivedFiller(value, parts);
    }
    const derived = this.hmac.asDigits(digitsOnly.length, ...parts);
    const payload = [Number(digitsOnly[0]), ...derived.slice(1, digitsOnly.length - 1)];
    const number = payload.join('') + luhnCheckDigit(payload);

    let index = 0;
    return value.replace(/\d/g, () => number[index++] ?? '0');
  }

  private iban(value: string, parts: string[]): string {
    const normalised = value.replace(/\s/g, '').toUpperCase();
    const country = /^[A-Z]{2}/.test(normalised)
      ? normalised.slice(0, 2)
      : pick(CORPORA.countryCodes, this.hmac.asInt(1 << 20, ...parts, 'country'));
    const length = normalised.length > 4 ? normalised.length - 4 : ibanBbanLength(country);
    const bban = this.hmac.asDigits(length, ...parts, 'bban').join('');
    return country + ibanCheckDigits(country, bban) + bban;
  }

  /**
   * Shifts by an offset derived from the row rather than the value, so every
   * date in a row moves together and intervals survive masking.
   */
  private shiftDate(value: unknown, context: MaskContext): unknown {
    const offset =
      this.hmac.asInt(731, context.datasetSalt, 'dateShift', context.rowKey ?? '') - 365;

    if (value instanceof Date) {
      return new Date(value.getTime() + offset * 86_400_000);
    }
    if (typeof value === 'string') {
      const parsed = Date.parse(value);
      if (!Number.isNaN(parsed)) {
        const shifted = new Date(parsed + offset * 86_400_000);
        return value.length <= 10 ? shifted.toISOString().slice(0, 10) : shifted.toISOString();
      }
    }
    return value;
  }

  /** Perturbs a number within a bounded factor, preserving sign and magnitude. */
  private jitter(value: unknown, context: MaskContext): unknown {
    const numeric = typeof value === 'number' ? value : Number(value);
    if (Number.isNaN(numeric)) {
      return value;
    }
    const factor =
      1 + (this.hmac.asUnit(context.datasetSalt, context.column ?? '', String(value)) * 2 - 1) * 0.15;
    const jittered = numeric * factor;
    return typeof value === 'number' ? jittered : jittered.toFixed(2);
  }
}
