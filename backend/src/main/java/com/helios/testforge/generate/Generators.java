package com.helios.testforge.generate;

import com.helios.testforge.introspect.TypeMod;
import com.helios.testforge.mask.Corpora;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The library of value generators.
 *
 * <p>Two recurring concerns shape most of these:
 *
 * <ul>
 *   <li><b>Uniqueness by construction.</b> A column under a UNIQUE constraint
 *       gets a generator that folds the row index into the value, so distinctness
 *       is guaranteed rather than hoped for. Retrying on collision would be both
 *       slower and non-deterministic in the number of draws it makes, which
 *       would break reproducibility.</li>
 *   <li><b>Bounds from the schema.</b> Lengths, precisions and scales come from
 *       the introspected column, so a generated value never fails on the very
 *       constraint the dataset is meant to exercise.</li>
 * </ul>
 */
public final class Generators {

    /** Datasets are centred on a fixed instant so a regenerated dataset is identical, not merely similar. */
    public static final LocalDate EPOCH = LocalDate.of(2026, 1, 1);

    /** Base 36 packs the most distinct values per character into an ASCII-safe alphabet. */
    private static final int RADIX = 36;

    /** Below this width, a unique value cannot be both readable and distinct. */
    private static final int MIN_READABLE_UNIQUE_LENGTH = 12;

    private Generators() {
    }

    // ------------------------------------------------------------- textual

    /**
     * An address whose local part is trimmed to fit rather than truncated after
     * the fact. Truncating would cut the discriminator off the end, which is
     * precisely the part that makes a unique column unique.
     */
    public static ValueGenerator email(boolean unique, Integer maxLength) {
        return (random, rowIndex) -> {
            String given = Corpora.pick(Corpora.GIVEN_NAMES, random.nextLong()).toLowerCase(Locale.ROOT);
            String family = Corpora.pick(Corpora.FAMILY_NAMES, random.nextLong()).toLowerCase(Locale.ROOT);
            String domain = Corpora.pick(Corpora.EMAIL_DOMAINS, random.nextLong());
            String discriminator = unique
                    ? Integer.toString(rowIndex, RADIX)
                    : Integer.toString(random.nextInt(1_000_000), RADIX);

            String candidate = given + "." + family + discriminator + "@" + domain;
            if (maxLength == null || candidate.length() <= maxLength) {
                return candidate;
            }
            // Shrink the name, never the discriminator or the domain.
            int fixed = discriminator.length() + 1 + domain.length();
            int room = maxLength - fixed;
            if (room < 1) {
                // Nothing survives but the discriminator; the planner caps row
                // counts for columns this narrow so it still stays unique.
                return fit(discriminator + "@" + domain, maxLength);
            }
            String name = (given + family).substring(0, Math.min(room, (given + family).length()));
            return name + discriminator + "@" + domain;
        };
    }

    /**
     * A value guaranteed distinct per row and guaranteed to fit.
     *
     * <p>Short unique columns - a {@code char(2)} country code, a {@code
     * varchar(8)} reference - cannot carry a readable value and a distinct
     * suffix at the same time, so below a readable threshold the value becomes
     * a base-36 encoding of the row index and nothing else. That is ugly and
     * correct, which beats readable and colliding.
     */
    public static ValueGenerator uniqueText(Integer maxLength) {
        int length = maxLength == null ? 32 : maxLength;
        if (length >= MIN_READABLE_UNIQUE_LENGTH) {
            return (random, rowIndex) -> {
                String suffix = Integer.toString(rowIndex, RADIX);
                int room = length - suffix.length() - 1;
                String word = Corpora.pick(Corpora.LOREM, random.nextLong());
                String head = word.length() > room ? word.substring(0, room) : word;
                return head + "-" + suffix;
            };
        }
        return (random, rowIndex) -> {
            String encoded = Integer.toString(rowIndex, RADIX);
            if (encoded.length() >= length) {
                // The planner caps row counts to the column's capacity, so this
                // only trims leading zeroes that were never significant.
                return encoded.substring(encoded.length() - length);
            }
            return "0".repeat(length - encoded.length()) + encoded;
        };
    }

    /**
     * How many distinct values a column can hold, so the planner can cap a
     * table rather than discovering the ceiling as a constraint violation
     * halfway through seeding.
     *
     * @return the capacity, or {@link Long#MAX_VALUE} when it is effectively unbounded
     */
    public static long uniqueCapacity(String udtName, Integer maxLength) {
        String type = udtName == null ? "" : udtName.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "int2" -> 32_767L;
            case "bool" -> 2L;
            case "varchar", "bpchar", "char" -> {
                if (maxLength == null || maxLength <= 0) {
                    yield Long.MAX_VALUE;
                }
                if (maxLength >= 13) {
                    yield Long.MAX_VALUE;
                }
                long capacity = 1;
                for (int i = 0; i < maxLength; i++) {
                    capacity *= RADIX;
                }
                yield capacity;
            }
            default -> Long.MAX_VALUE;
        };
    }

    public static ValueGenerator givenName(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> Corpora.pick(Corpora.GIVEN_NAMES, random.nextLong()));
    }

    public static ValueGenerator familyName(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> Corpora.pick(Corpora.FAMILY_NAMES, random.nextLong()));
    }

    public static ValueGenerator fullName(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) ->
                Corpora.pick(Corpora.GIVEN_NAMES, random.nextLong()) + " "
                        + Corpora.pick(Corpora.FAMILY_NAMES, random.nextLong()));
    }

    public static ValueGenerator username(boolean unique, Integer maxLength) {
        if (unique) {
            return uniqueText(maxLength);
        }
        return truncated(maxLength, (random, rowIndex) ->
                Corpora.pick(Corpora.GIVEN_NAMES, random.nextLong()).toLowerCase(Locale.ROOT)
                        + "_" + Corpora.pick(Corpora.LOREM, random.nextLong())
                        + random.nextInt(1_000));
    }

    public static ValueGenerator phone(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> String.format("+1 (%03d) %03d-%04d",
                200 + random.nextInt(700), 200 + random.nextInt(700), random.nextInt(10_000)));
    }

    /** Reserved 900-999 area, so a generated SSN can never be a real one. */
    public static ValueGenerator ssn() {
        return (random, rowIndex) -> String.format("%03d-%02d-%04d",
                900 + random.nextInt(100), 1 + random.nextInt(99), 1 + random.nextInt(9_999));
    }

    /** A Luhn-valid test card number in the 4-BIN range. */
    public static ValueGenerator creditCard() {
        return (random, rowIndex) -> {
            int[] payload = new int[15];
            payload[0] = 4;
            for (int i = 1; i < payload.length; i++) {
                payload[i] = random.nextInt(10);
            }
            StringBuilder number = new StringBuilder(16);
            for (int digit : payload) {
                number.append(digit);
            }
            return number.append(com.helios.testforge.mask.Checksums.luhnCheckDigit(payload)).toString();
        };
    }

    public static ValueGenerator iban() {
        return (random, rowIndex) -> {
            String country = Corpora.pick(Corpora.COUNTRY_CODES, random.nextLong());
            int length = Corpora.ibanBbanLength(country);
            StringBuilder bban = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                bban.append(random.nextInt(10));
            }
            return country + com.helios.testforge.mask.Checksums.ibanCheckDigits(country, bban.toString()) + bban;
        };
    }

    public static ValueGenerator streetAddress(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> (1 + random.nextInt(9_999)) + " "
                + Corpora.pick(Corpora.STREET_NAMES, random.nextLong()) + " "
                + Corpora.pick(Corpora.STREET_TYPES, random.nextLong()));
    }

    public static ValueGenerator fromCorpus(List<String> corpus, Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> Corpora.pick(corpus, random.nextLong()));
    }

    public static ValueGenerator postalCode(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> String.format("%05d", random.nextInt(100_000)));
    }

    public static ValueGenerator ipv4() {
        return (random, rowIndex) -> {
            // 198.51.100.0/24 and 203.0.113.0/24 are reserved for documentation.
            return random.nextBoolean()
                    ? "198.51.100." + (1 + random.nextInt(254))
                    : "203.0.113." + (1 + random.nextInt(254));
        };
    }

    public static ValueGenerator macAddress() {
        return (random, rowIndex) -> String.format("02:%02x:%02x:%02x:%02x:%02x",
                random.nextInt(256), random.nextInt(256), random.nextInt(256),
                random.nextInt(256), random.nextInt(256));
    }

    public static ValueGenerator url(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> "https://"
                + Corpora.pick(Corpora.EMAIL_DOMAINS, random.nextLong())
                + "/" + Corpora.pick(Corpora.LOREM, random.nextLong())
                + "/" + Corpora.pick(Corpora.LOREM, random.nextLong()));
    }

    public static ValueGenerator slug(boolean unique, Integer maxLength) {
        if (unique) {
            return uniqueText(maxLength);
        }
        return truncated(maxLength, (random, rowIndex) ->
                Corpora.pick(Corpora.LOREM, random.nextLong()) + "-"
                        + Corpora.pick(Corpora.LOREM, random.nextLong()));
    }

    public static ValueGenerator title(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> {
            String first = Corpora.pick(Corpora.LOREM, random.nextLong());
            String second = Corpora.pick(Corpora.LOREM, random.nextLong());
            return Character.toUpperCase(first.charAt(0)) + first.substring(1) + " " + second;
        });
    }

    /** A few sentences of filler, sized to the column rather than to a fixed length. */
    public static ValueGenerator freeText(Integer maxLength) {
        int words = maxLength == null ? 24 : Math.max(3, Math.min(48, maxLength / 8));
        return truncated(maxLength, (random, rowIndex) -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < words; i++) {
                if (i > 0) {
                    text.append(' ');
                }
                text.append(Corpora.pick(Corpora.LOREM, random.nextLong()));
            }
            text.setCharAt(0, Character.toUpperCase(text.charAt(0)));
            return text.append('.').toString();
        });
    }

    /** A hex digest shaped like a stored password hash, never a real credential. */
    public static ValueGenerator opaqueToken(String prefix, int hexLength, Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) -> {
            StringBuilder hex = new StringBuilder(hexLength);
            for (int i = 0; i < hexLength; i++) {
                hex.append(Integer.toHexString(random.nextInt(16)));
            }
            return prefix + hex;
        });
    }

    public static ValueGenerator enumLabel(List<String> labels) {
        return (random, rowIndex) -> labels.isEmpty() ? null : Corpora.pick(labels, random.nextLong());
    }

    /** Filler text whose length respects the column, used when nothing more specific fits. */
    public static ValueGenerator genericText(Integer maxLength) {
        return truncated(maxLength, (random, rowIndex) ->
                Corpora.pick(Corpora.LOREM, random.nextLong()) + "-" + Math.abs(random.nextInt(100_000)));
    }

    // ------------------------------------------------------------- numeric

    public static ValueGenerator smallInt(boolean unique) {
        return (random, rowIndex) -> unique
                ? (short) (rowIndex + 1)
                : (short) (1 + random.nextInt(30_000));
    }

    public static ValueGenerator integer(boolean unique) {
        return (random, rowIndex) -> unique ? rowIndex + 1 : 1 + random.nextInt(1_000_000);
    }

    public static ValueGenerator bigInt(boolean unique) {
        return (random, rowIndex) -> unique ? (long) rowIndex + 1 : 1L + random.nextLong(1_000_000_000L);
    }

    public static ValueGenerator quantity() {
        return (random, rowIndex) -> 1 + random.nextInt(500);
    }

    /**
     * A monetary amount at the column's declared scale. Values cluster low with
     * an occasional large one, which is closer to a real order-value distribution
     * than a uniform draw and exercises rounding and aggregation code better.
     */
    public static ValueGenerator money(Integer precision, Integer scale) {
        int digits = scale == null ? 2 : Math.min(scale, 4);
        int maxUnits = boundedMagnitude(precision, digits);
        return (random, rowIndex) -> {
            double roll = random.nextDouble();
            int units = roll < 0.9
                    ? 100 + random.nextInt(Math.max(2, Math.min(maxUnits, 50_000)))
                    : 50_000 + random.nextInt(Math.max(2, Math.min(maxUnits, 5_000_000)));
            return BigDecimal.valueOf(units, 2).setScale(digits, RoundingMode.HALF_UP);
        };
    }

    public static ValueGenerator decimal(Integer precision, Integer scale) {
        int digits = scale == null ? 2 : scale;
        int maxUnits = boundedMagnitude(precision, digits);
        return (random, rowIndex) -> BigDecimal
                .valueOf(random.nextInt(Math.max(2, maxUnits)), digits)
                .setScale(digits, RoundingMode.HALF_UP);
    }

    public static ValueGenerator percentage(Integer scale) {
        int digits = scale == null ? 2 : Math.min(scale, 4);
        return (random, rowIndex) -> BigDecimal.valueOf(random.nextInt(10_001), 2)
                .setScale(digits, RoundingMode.HALF_UP);
    }

    public static ValueGenerator doubleValue() {
        return (random, rowIndex) -> Math.round(random.nextDouble() * 1_000_00d) / 100d;
    }

    public static ValueGenerator floatValue() {
        return (random, rowIndex) -> (float) (Math.round(random.nextDouble() * 10_000d) / 100d);
    }

    public static ValueGenerator latitude() {
        return (random, rowIndex) -> BigDecimal.valueOf(random.nextDouble() * 180 - 90)
                .setScale(6, RoundingMode.HALF_UP);
    }

    public static ValueGenerator longitude() {
        return (random, rowIndex) -> BigDecimal.valueOf(random.nextDouble() * 360 - 180)
                .setScale(6, RoundingMode.HALF_UP);
    }

    public static ValueGenerator bool() {
        return (random, rowIndex) -> random.nextBoolean();
    }

    /** Weighted so flags are mostly false, which is how flags behave in practice. */
    public static ValueGenerator flag(int truePercent) {
        return (random, rowIndex) -> random.nextInt(100) < truePercent;
    }

    // ------------------------------------------------------------ temporal

    public static ValueGenerator date(int daysBack, int daysForward) {
        return (random, rowIndex) -> EPOCH.plusDays(random.nextInt(daysBack + daysForward + 1) - daysBack);
    }

    public static ValueGenerator dateOfBirth() {
        // Adults between 18 and 80 as of the fixed epoch.
        return (random, rowIndex) -> EPOCH.minusDays(18L * 365 + random.nextInt(62 * 365));
    }

    public static ValueGenerator timestamp(int daysBack, int daysForward) {
        return (random, rowIndex) -> EPOCH
                .plusDays(random.nextInt(daysBack + daysForward + 1) - daysBack)
                .atStartOfDay()
                .plusSeconds(random.nextInt(86_400));
    }

    public static ValueGenerator timestampTz(int daysBack, int daysForward) {
        return (random, rowIndex) -> EPOCH
                .plusDays(random.nextInt(daysBack + daysForward + 1) - daysBack)
                .atStartOfDay()
                .plusSeconds(random.nextInt(86_400))
                .atOffset(ZoneOffset.UTC);
    }

    public static ValueGenerator time() {
        return (random, rowIndex) -> LocalTime.ofSecondOfDay(random.nextInt(86_400));
    }

    /** A PostgreSQL interval literal; the seeder binds it as an untyped parameter. */
    public static ValueGenerator interval() {
        return (random, rowIndex) -> (1 + random.nextInt(90)) + " days " + random.nextInt(24) + " hours";
    }

    public static ValueGenerator durationMillis() {
        return (random, rowIndex) -> (long) (10 + random.nextInt(600_000));
    }

    // --------------------------------------------------------------- other

    /**
     * A UUID derived from the cell's stream rather than {@code randomUUID()},
     * so it is reproducible. Version and variant bits are set so the value is a
     * well-formed v4 UUID.
     */
    public static ValueGenerator uuid() {
        return (random, rowIndex) -> {
            long high = random.nextLong();
            long low = random.nextLong();
            high = (high & ~0xF000L) | 0x4000L;
            low = (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return new UUID(high, low);
        };
    }

    public static ValueGenerator jsonDocument() {
        return (random, rowIndex) -> "{"
                + "\"source\":\"testforge\","
                + "\"row\":" + rowIndex + ","
                + "\"" + Corpora.pick(Corpora.LOREM, random.nextLong()) + "\":"
                + "\"" + Corpora.pick(Corpora.LOREM, random.nextLong()) + "\","
                + "\"score\":" + random.nextInt(100)
                + "}";
    }

    public static ValueGenerator bytes(int length) {
        return (random, rowIndex) -> {
            byte[] payload = new byte[length];
            for (int i = 0; i < length; i++) {
                payload[i] = (byte) random.nextInt(256);
            }
            return payload;
        };
    }

    public static ValueGenerator textArray(int minSize, int maxSize) {
        return (random, rowIndex) -> {
            int size = minSize + random.nextInt(Math.max(1, maxSize - minSize + 1));
            String[] values = new String[size];
            for (int i = 0; i < size; i++) {
                values[i] = Corpora.pick(Corpora.LOREM, random.nextLong());
            }
            return values;
        };
    }

    public static ValueGenerator integerArray(int minSize, int maxSize) {
        return (random, rowIndex) -> {
            int size = minSize + random.nextInt(Math.max(1, maxSize - minSize + 1));
            Integer[] values = new Integer[size];
            for (int i = 0; i < size; i++) {
                values[i] = random.nextInt(1_000);
            }
            return values;
        };
    }

    // ------------------------------------------------- constraint-respecting

    /** An integer drawn from an inclusive range, for a column with a CHECK bound. */
    public static ValueGenerator integerInRange(long min, long max, String udtName) {
        long low = Math.min(min, max);
        long high = Math.max(min, max);
        long span = high - low + 1;
        return (random, rowIndex) -> {
            long value = low + Math.floorMod(random.nextLong(), span);
            return switch (TypeMod.base(udtName)) {
                case "int2" -> (short) value;
                case "int8" -> value;
                default -> (int) value;
            };
        };
    }

    /** A decimal drawn from an inclusive range at the column's scale. */
    public static ValueGenerator decimalInRange(BigDecimal min, BigDecimal max, Integer scale) {
        int digits = scale == null ? 2 : scale;
        BigDecimal low = min.min(max);
        BigDecimal span = max.max(min).subtract(low);
        return (random, rowIndex) -> low
                .add(span.multiply(BigDecimal.valueOf(random.nextDouble())))
                .setScale(digits, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------- helpers

    /** Wraps a generator so its string output never exceeds the column's declared length. */
    static ValueGenerator truncated(Integer maxLength, ValueGenerator delegate) {
        if (maxLength == null || maxLength <= 0) {
            return delegate;
        }
        return (random, rowIndex) -> {
            Object value = delegate.generate(random, rowIndex);
            if (value instanceof String text && text.length() > maxLength) {
                return text.substring(0, maxLength);
            }
            return value;
        };
    }

    /**
     * The largest unscaled value that fits a {@code numeric(precision, scale)}
     * column, capped so it also fits an int.
     */
    private static int boundedMagnitude(Integer precision, int scale) {
        int integerDigits = precision == null ? 9 : Math.max(1, precision - scale);
        int capped = Math.min(integerDigits + scale, 9);
        return (int) Math.pow(10, capped) - 1;
    }

    /** Trims to fit, keeping the tail - the end of a generated value carries its distinctness. */
    static String fit(String value, Integer maxLength) {
        if (maxLength == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    /** UTF-8 length, used where a column's limit is in bytes rather than characters. */
    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Convenience for tests and the console preview. */
    static LocalDateTime epochAtMidnight() {
        return EPOCH.atStartOfDay();
    }

    /** Convenience for tests and the console preview. */
    static OffsetDateTime epochUtc() {
        return EPOCH.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
