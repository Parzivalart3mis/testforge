package com.helios.testforge.mask;

import com.helios.testforge.config.TestForgeProperties;
import com.helios.testforge.domain.request.MaskStrategy;
import com.helios.testforge.domain.request.MaskingRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Applies a {@link MaskStrategy} to a value, deterministically.
 *
 * <p>The design constraint that shapes everything here is that a masked dataset
 * still has to be <em>usable</em>. Replacing every sensitive value with the same
 * constant is trivially safe and completely useless: joins break, uniqueness
 * constraints collide, and validation code rejects the rows. So each strategy
 * preserves the properties the schema and the application depend on - length,
 * character class, punctuation, checksum validity, uniqueness, ordering - while
 * destroying the link to the original.
 *
 * <p>Every strategy derives from {@link Hmac}, so nothing here consults a random
 * number generator or a clock. Re-running the same dataset produces byte-identical
 * masked values.
 */
@Component
public class MaskingEngine {

    /**
     * Area, group and serial ranges the Social Security Administration never
     * issues. Masked SSNs are placed inside these, so a masked value is both
     * well-formed and provably not a real number.
     */
    private static final int RESERVED_SSN_AREA = 900;

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?");
    private static final java.util.regex.Pattern IPV6_HINT =
            java.util.regex.Pattern.compile("[0-9a-fA-F:]+(/\\d{1,3})?");
    private static final java.util.regex.Pattern MAC =
            java.util.regex.Pattern.compile("([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}");

    private final Hmac hmac;
    private final String redactionToken;

    // Explicit: the package-private constructor below is a test seam, and two
    // candidate constructors leave Spring unable to choose between them.
    @Autowired
    public MaskingEngine(TestForgeProperties properties) {
        String key = properties.masking().key();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("""
                    testforge.masking.key is not set. The platform refuses to start rather than \
                    mask with a default key: a guessable key makes every masked value reversible \
                    by anyone who knows the default. Set TESTFORGE_MASKING_KEY, or run with the \
                    'dev' profile for local work.""");
        }
        this.hmac = Hmac.of(key);
        this.redactionToken = properties.masking().redactionToken();
    }

    MaskingEngine(String key, String redactionToken) {
        this.hmac = Hmac.of(key);
        this.redactionToken = redactionToken;
    }

    /** Test seam: an engine with a fixed key. */
    public static MaskingEngine withKey(String key) {
        return new MaskingEngine(key, "[REDACTED]");
    }

    /**
     * Masks one value.
     *
     * @param strategy how to transform it
     * @param value    the value; null passes through untouched under every strategy
     * @param context  the derivation scope
     * @param rule     the rule that selected the strategy, carrying its options; may be null
     * @return the masked value, of a type the column can still hold
     */
    public Object mask(MaskStrategy strategy, Object value, MaskContext context, MaskingRule rule) {
        if (strategy == MaskStrategy.PRESERVE || value == null) {
            return value;
        }
        if (strategy == MaskStrategy.NULLIFY) {
            return null;
        }
        return switch (strategy) {
            case DATE_SHIFT -> shiftTemporal(value, context, rule);
            case NUMERIC_JITTER -> jitterNumeric(value, context, rule);
            default -> maskText(strategy, String.valueOf(value), context, rule);
        };
    }

    /** Masks a textual value. */
    public String maskText(MaskStrategy strategy, String value, MaskContext context, MaskingRule rule) {
        if (value == null) {
            return null;
        }
        String[] parts = context.parts(value);
        return switch (strategy) {
            case PRESERVE -> value;
            case NULLIFY -> null;
            case REDACT -> redactionToken;
            case HASH -> hmac.asHex(lengthOption(rule, 32), parts);
            case TOKENIZE -> "tok_" + hmac.asHex(16, parts);
            case PARTIAL -> partial(value, rule, parts);
            case EMAIL -> email(value, rule, parts);
            case PHONE -> formatPreservingDigits(value, parts);
            case NAME -> name(value, parts);
            case SSN -> ssn(value, parts);
            case CREDIT_CARD -> creditCard(value, parts);
            case IBAN -> iban(value, parts);
            case DATE_SHIFT, NUMERIC_JITTER -> String.valueOf(mask(strategy, value, context, rule));
        };
    }

    // ------------------------------------------------------------ strategies

    /**
     * Keeps a prefix and suffix, replaces the middle with a derived filler of
     * the same length. Length is preserved, so a column with a length limit or a
     * fixed-width format still validates.
     */
    private String partial(String value, MaskingRule rule, String[] parts) {
        // A network address is structurally constrained in a way per-character
        // substitution cannot respect: replacing the digits of 198.51.100.42
        // happily produces 138.21.339.32, and inet rejects it. Route those to a
        // masker that understands the format.
        String network = maskNetworkAddress(value, parts);
        if (network != null) {
            return network;
        }

        int keepPrefix = Math.max(0, rule == null ? 1 : rule.optionInt("keepPrefix", 1));
        int keepSuffix = Math.max(0, rule == null ? 1 : rule.optionInt("keepSuffix", 1));

        if (value.length() <= keepPrefix + keepSuffix) {
            // Too short to reveal anything from - fall back to a full replacement.
            return derivedFiller(value, parts);
        }
        String prefix = value.substring(0, keepPrefix);
        String suffix = value.substring(value.length() - keepSuffix);
        String middle = derivedFiller(value.substring(keepPrefix, value.length() - keepSuffix), parts);
        return prefix + middle + suffix;
    }

    /**
     * Masks an IP or MAC address into a valid one in a reserved range.
     *
     * <p>The output blocks are the ones reserved for documentation and testing -
     * TEST-NET-1/2/3 for IPv4, 2001:db8::/32 for IPv6, and a locally-administered
     * OUI for MAC - so a masked address is both well-formed and unmistakably not
     * a real host. That matters here: test traffic aimed at a masked address
     * should go nowhere rather than somewhere.
     *
     * @return the masked address, or null when the value is not one
     */
    private String maskNetworkAddress(String value, String[] parts) {
        String trimmed = value.trim();

        if (IPV4.matcher(trimmed).matches()) {
            String[] blocks = {"192.0.2", "198.51.100", "203.0.113"};
            String block = blocks[hmac.asInt(blocks.length, concat(parts, "block"))];
            int host = 1 + hmac.asInt(254, concat(parts, "host"));
            return block + "." + host;
        }
        if (MAC.matcher(trimmed).matches()) {
            int[] digits = hmac.asDigits(10, concat(parts, "mac"));
            return String.format("02:%x%x:%x%x:%x%x:%x%x:%x%x",
                    digits[0], digits[1], digits[2], digits[3], digits[4],
                    digits[5], digits[6], digits[7], digits[8], digits[9]);
        }
        if (trimmed.indexOf(':') >= 0 && IPV6_HINT.matcher(trimmed).matches()) {
            return "2001:db8:" + hmac.asHex(4, concat(parts, "v6a"))
                    + ":" + hmac.asHex(4, concat(parts, "v6b"))
                    + "::" + hmac.asHex(4, concat(parts, "v6c"));
        }
        return null;
    }

    /**
     * Replaces a string with derived characters of the same length, matching each
     * character's class so digits stay digits and separators stay separators.
     */
    private String derivedFiller(String original, String[] parts) {
        int[] digits = hmac.asDigits(original.length(), parts);
        StringBuilder filler = new StringBuilder(original.length());
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isDigit(c)) {
                filler.append((char) ('0' + digits[i]));
            } else if (Character.isUpperCase(c)) {
                filler.append((char) ('A' + (digits[i] * 26 / 10)));
            } else if (Character.isLetter(c)) {
                filler.append((char) ('a' + (digits[i] * 26 / 10)));
            } else {
                filler.append(c);
            }
        }
        return filler.toString();
    }

    /**
     * Rebuilds an address as a derived local part at a reserved domain.
     *
     * <p>The domain is replaced by default. Preserving it would leak which
     * company or provider each subject belongs to, which is often the most
     * identifying part of the record in a B2B dataset. Set the
     * {@code preserveDomain} option when the domain itself is under test.
     */
    private String email(String value, MaskingRule rule, String[] parts) {
        boolean preserveDomain = rule != null && Boolean.parseBoolean(rule.option("preserveDomain", "false"));
        int at = value.lastIndexOf('@');
        String domain;
        if (preserveDomain && at >= 0 && at < value.length() - 1) {
            domain = value.substring(at + 1);
        } else {
            domain = Corpora.pick(Corpora.EMAIL_DOMAINS, hmac.asLong(concat(parts, "domain")));
        }

        String given = Corpora.pick(Corpora.GIVEN_NAMES, hmac.asLong(concat(parts, "given")));
        String family = Corpora.pick(Corpora.FAMILY_NAMES, hmac.asLong(concat(parts, "family")));
        // A numeric suffix keeps the local part unique enough that a UNIQUE
        // constraint on the column survives a large dataset.
        int discriminator = hmac.asInt(100_000, concat(parts, "discriminator"));
        String local = (given + "." + family).toLowerCase(Locale.ROOT) + discriminator;

        return local + "@" + domain;
    }

    /**
     * Replaces every digit, keeps every separator. A masked phone number has the
     * same shape as the original, so format assertions and column widths hold.
     */
    private String formatPreservingDigits(String value, String[] parts) {
        int digitCount = (int) value.chars().filter(Character::isDigit).count();
        if (digitCount == 0) {
            return value;
        }
        int[] digits = hmac.asDigits(digitCount, parts);
        StringBuilder masked = new StringBuilder(value.length());
        int index = 0;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                int digit = digits[index];
                // Avoid a leading zero, which several phone formats reject.
                if (index == 0 && digit == 0) {
                    digit = 5;
                }
                masked.append((char) ('0' + digit));
                index++;
            } else {
                masked.append(c);
            }
        }
        return masked.toString();
    }

    /**
     * Substitutes a different name, preserving whether the original looked like
     * a single name or a full name so the shape of the column stays consistent.
     */
    private String name(String value, String[] parts) {
        String given = Corpora.pick(Corpora.GIVEN_NAMES, hmac.asLong(concat(parts, "given")));
        String family = Corpora.pick(Corpora.FAMILY_NAMES, hmac.asLong(concat(parts, "family")));
        return value.trim().contains(" ") ? given + " " + family : given;
    }

    /**
     * A well-formed SSN in the 900-999 area range, which is reserved and never
     * issued - so the value passes format validation and cannot collide with a
     * real person's number.
     */
    private String ssn(String value, String[] parts) {
        int area = RESERVED_SSN_AREA + hmac.asInt(100, concat(parts, "area"));
        int group = 1 + hmac.asInt(99, concat(parts, "group"));
        int serial = 1 + hmac.asInt(9999, concat(parts, "serial"));
        String formatted = String.format("%03d-%02d-%04d", area, group, serial);
        // Keep the original's punctuation convention.
        return value.contains("-") ? formatted : formatted.replace("-", "");
    }

    /**
     * A Luhn-valid card number of the same length, keeping the leading digit so
     * brand detection still classifies it the same way.
     */
    private String creditCard(String value, String[] parts) {
        String digitsOnly = value.replaceAll("[^0-9]", "");
        if (digitsOnly.length() < 4) {
            return derivedFiller(value, parts);
        }
        int length = digitsOnly.length();
        int[] payload = new int[length - 1];
        payload[0] = digitsOnly.charAt(0) - '0';

        int[] derived = hmac.asDigits(length, parts);
        for (int i = 1; i < payload.length; i++) {
            payload[i] = derived[i];
        }
        StringBuilder number = new StringBuilder(length);
        for (int digit : payload) {
            number.append(digit);
        }
        number.append(Checksums.luhnCheckDigit(payload));

        return reapplySeparators(value, number.toString());
    }

    /** A checksum-valid IBAN in the same country, at that country's correct length. */
    private String iban(String value, String[] parts) {
        String normalised = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        String country = normalised.length() >= 2 && Character.isLetter(normalised.charAt(0))
                ? normalised.substring(0, 2)
                : Corpora.pick(Corpora.COUNTRY_CODES, hmac.asLong(concat(parts, "country")));

        int bbanLength = normalised.length() > 4 ? normalised.length() - 4 : Corpora.ibanBbanLength(country);
        int[] digits = hmac.asDigits(bbanLength, concat(parts, "bban"));
        StringBuilder bban = new StringBuilder(bbanLength);
        for (int digit : digits) {
            bban.append(digit);
        }

        return country + Checksums.ibanCheckDigits(country, bban.toString()) + bban;
    }

    /**
     * Shifts a date or timestamp by a deterministic offset.
     *
     * <p>The offset is derived from the row key, not the value, so every date in
     * a row moves by the same amount. An order placed three days after signup is
     * still placed three days after signup once masked - which is exactly the
     * property that makes a masked dataset useful for testing date logic.
     */
    private Object shiftTemporal(Object value, MaskContext context, MaskingRule rule) {
        int maxDays = Math.max(1, rule == null ? 365 : rule.optionInt("maxDays", 365));
        long offset = hmac.asInt(maxDays * 2 + 1, context.rowParts("dateShift")) - maxDays;

        return switch (value) {
            case LocalDate date -> date.plusDays(offset);
            case LocalDateTime dateTime -> dateTime.plusDays(offset);
            case OffsetDateTime offsetDateTime -> offsetDateTime.plusDays(offset);
            case java.sql.Date sqlDate -> java.sql.Date.valueOf(sqlDate.toLocalDate().plusDays(offset));
            case java.sql.Timestamp timestamp ->
                    java.sql.Timestamp.valueOf(timestamp.toLocalDateTime().plusDays(offset));
            case String text -> shiftTextualDate(text, offset);
            default -> value;
        };
    }

    private Object shiftTextualDate(String text, long offset) {
        try {
            return LocalDate.parse(text).plusDays(offset).toString();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text).plusDays(offset).toString();
            } catch (RuntimeException alsoIgnored) {
                return text;
            }
        }
    }

    /**
     * Perturbs a number by a bounded factor, preserving sign, rough magnitude and
     * scale. Aggregate queries over a masked column still return plausible
     * numbers; individual values are no longer the subject's.
     */
    private Object jitterNumeric(Object value, MaskContext context, MaskingRule rule) {
        int percent = Math.max(1, Math.min(100, rule == null ? 15 : rule.optionInt("percent", 15)));
        double spread = percent / 100d;
        double factor = 1 + (hmac.asUnitDouble(context.parts(String.valueOf(value))) * 2 - 1) * spread;

        return switch (value) {
            case BigDecimal decimal -> decimal
                    .multiply(BigDecimal.valueOf(factor), MathContext.DECIMAL64)
                    .setScale(decimal.scale(), RoundingMode.HALF_UP);
            case Double d -> d * factor;
            case Float f -> (float) (f * factor);
            case Long l -> Math.round(l * factor);
            case Integer i -> (int) Math.round(i * factor);
            case Short s -> (short) Math.round(s * factor);
            case String text -> jitterTextualNumber(text, factor);
            default -> value;
        };
    }

    private Object jitterTextualNumber(String text, double factor) {
        try {
            BigDecimal decimal = new BigDecimal(text);
            return decimal.multiply(BigDecimal.valueOf(factor), MathContext.DECIMAL64)
                    .setScale(decimal.scale(), RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException e) {
            return text;
        }
    }

    // --------------------------------------------------------------- helpers

    /** Re-inserts the original's non-digit characters into a fresh digit string. */
    private static String reapplySeparators(String original, String digits) {
        StringBuilder result = new StringBuilder(original.length());
        int index = 0;
        for (char c : original.toCharArray()) {
            if (Character.isDigit(c)) {
                result.append(index < digits.length() ? digits.charAt(index++) : '0');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static int lengthOption(MaskingRule rule, int fallback) {
        int length = rule == null ? fallback : rule.optionInt("length", fallback);
        return Math.max(4, Math.min(64, length));
    }

    private static String[] concat(String[] parts, String extra) {
        String[] combined = new String[parts.length + 1];
        System.arraycopy(parts, 0, combined, 0, parts.length);
        combined[parts.length] = extra;
        return combined;
    }
}
