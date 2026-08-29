package com.helios.testforge.mask;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Check-digit algorithms, so masked card numbers and IBANs are still valid.
 *
 * <p>This matters more than it sounds. Application code under test frequently
 * validates these formats before doing anything else; a masked card number that
 * fails Luhn turns a test dataset into a dataset that only ever exercises the
 * validation error path.
 */
public final class Checksums {

    private Checksums() {
    }

    /** The digit that makes the payload pass the Luhn check. */
    public static int luhnCheckDigit(int[] payload) {
        int sum = 0;
        boolean doubling = true;
        for (int i = payload.length - 1; i >= 0; i--) {
            int digit = payload[i];
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

    public static boolean isLuhnValid(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() < 2) {
            return false;
        }
        int[] payload = new int[digits.length() - 1];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = digits.charAt(i) - '0';
        }
        return luhnCheckDigit(payload) == digits.charAt(digits.length() - 1) - '0';
    }

    /**
     * ISO 7064 MOD 97-10 check digits for an IBAN.
     *
     * @param countryCode two-letter country code
     * @param bban        the basic bank account number, already at the country's length
     */
    public static String ibanCheckDigits(String countryCode, String bban) {
        BigInteger numeric = new BigInteger(toNumeric(bban + countryCode + "00"));
        int check = 98 - numeric.mod(BigInteger.valueOf(97)).intValue();
        return String.format("%02d", check);
    }

    public static boolean isIbanValid(String iban) {
        String normalised = iban.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (normalised.length() < 5) {
            return false;
        }
        String rearranged = normalised.substring(4) + normalised.substring(0, 4);
        return new BigInteger(toNumeric(rearranged)).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Letters become their 1-based alphabet position offset by 9, per ISO 13616. */
    private static String toNumeric(String value) {
        StringBuilder numeric = new StringBuilder(value.length() * 2);
        for (char c : value.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (Character.isLetter(c)) {
                numeric.append(c - 'A' + 10);
            }
        }
        return numeric.toString();
    }
}
