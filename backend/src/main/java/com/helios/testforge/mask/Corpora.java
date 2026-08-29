package com.helios.testforge.mask;

import java.util.List;

/**
 * Fixed word lists that masked values are drawn from.
 *
 * <p>These are deliberately small, fixed and committed rather than generated:
 * a masked value has to be reproducible across service restarts, deployments
 * and versions, which rules out anything derived from a library's internal
 * ordering. Adding to a list is safe; reordering or removing entries changes
 * every previously produced dataset, so the lists are append-only.
 *
 * <p>Domains all sit under RFC 2606 reserved names, so a masked address can
 * never resolve to a real mail server if test code tries to send to it.
 */
public final class Corpora {

    /** Reserved by RFC 2606 and RFC 6761 - guaranteed never to be deliverable. */
    public static final List<String> EMAIL_DOMAINS = List.of(
            "example.com", "example.net", "example.org",
            "mail.example.com", "corp.example.com", "test.example.com",
            "inbox.example.net", "users.example.org", "accounts.example.com",
            "notifications.example.net", "billing.example.org", "support.example.com");

    public static final List<String> GIVEN_NAMES = List.of(
            "Avery", "Blake", "Cameron", "Devon", "Emerson", "Finley", "Gray", "Harper",
            "Indigo", "Jordan", "Kai", "Logan", "Morgan", "Noel", "Oakley", "Parker",
            "Quinn", "Reese", "Sawyer", "Tatum", "Uriel", "Vale", "Wren", "Xen",
            "Yael", "Zion", "Adair", "Brett", "Casey", "Dallas", "Ellis", "Frankie",
            "Greer", "Hayden", "Ira", "Jamie", "Kendall", "Lane", "Marlowe", "Nico",
            "Ocean", "Peyton", "Rory", "Sage", "Toby", "Umber", "Vesper", "Winter");

    public static final List<String> FAMILY_NAMES = List.of(
            "Ashford", "Blackwood", "Castellan", "Draycott", "Ellsworth", "Fairbairn",
            "Grantley", "Hollowell", "Ironside", "Jessamine", "Kestrel", "Lockhart",
            "Marchetti", "Northcote", "Oakhurst", "Pemberton", "Quillon", "Ravenscroft",
            "Sterling", "Thornbury", "Underhill", "Vandermeer", "Westbrook", "Yarrow",
            "Ziegler", "Aldergate", "Brightwater", "Calloway", "Dunmore", "Everly",
            "Fenwick", "Galloway", "Harkness", "Inglewood", "Kingsley", "Larkspur");

    public static final List<String> STREET_TYPES = List.of(
            "Street", "Avenue", "Road", "Lane", "Way", "Drive", "Court", "Place",
            "Terrace", "Boulevard", "Crescent", "Walk");

    public static final List<String> STREET_NAMES = List.of(
            "Alder", "Birch", "Cedar", "Dogwood", "Elm", "Fir", "Ginkgo", "Hawthorn",
            "Ironwood", "Juniper", "Katsura", "Linden", "Maple", "Nyssa", "Olive",
            "Poplar", "Quince", "Redwood", "Sycamore", "Tupelo", "Umbrella", "Viburnum",
            "Willow", "Yew", "Zelkova");

    public static final List<String> CITIES = List.of(
            "Ashbourne", "Brackenford", "Cliffmere", "Dunwich Falls", "Eastvale",
            "Fernhollow", "Glenmarch", "Harrowgate", "Innisford", "Jarrowmead",
            "Kirkstall", "Lynnfield", "Marchwood", "Netherby", "Oldcastle",
            "Pinehaven", "Quarryside", "Rookhaven", "Stonebridge", "Thornfield",
            "Upperton", "Vinemount", "Westmarch", "Yarrowdale");

    public static final List<String> REGIONS = List.of(
            "Northshire", "Eastmarch", "Southhold", "Westreach", "Midvale",
            "Highmoor", "Lowfen", "Farhaven", "Nearcliff", "Overton",
            "Underwood County", "Riverbend");

    public static final List<String> COMPANIES = List.of(
            "Northwind Systems", "Brightpath Labs", "Meridian Works", "Cobalt Analytics",
            "Ironvale Logistics", "Quillmark Media", "Sparrow Robotics", "Tidewater Foods",
            "Umbra Security", "Vantage Retail", "Wexford Health", "Zephyr Transit");

    public static final List<String> JOB_TITLES = List.of(
            "Platform Engineer", "Data Steward", "Release Manager", "Quality Analyst",
            "Site Reliability Engineer", "Product Designer", "Solutions Architect",
            "Technical Writer", "Support Specialist", "Engineering Manager",
            "Database Administrator", "Security Analyst");

    public static final List<String> DEPARTMENTS = List.of(
            "Platform", "Data", "Quality", "Security", "Operations", "Support",
            "Design", "Infrastructure", "Payments", "Identity", "Reporting", "Tooling");

    public static final List<String> PRODUCTS = List.of(
            "Aluminium Kettle", "Bamboo Desk Mat", "Ceramic Mug", "Down Duvet",
            "Electric Grinder", "Felt Organiser", "Glass Carafe", "Hemp Tote",
            "Insulated Bottle", "Jute Runner", "Knit Throw", "Linen Napkins",
            "Merino Socks", "Nylon Duffel", "Oak Cutting Board", "Porcelain Bowl",
            "Quilted Jacket", "Rattan Basket", "Steel Skillet", "Terracotta Planter");

    public static final List<String> CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "SEK", "NOK", "NZD");

    public static final List<String> COUNTRIES = List.of(
            "United States", "Canada", "United Kingdom", "Germany", "France",
            "Netherlands", "Sweden", "Japan", "Australia", "New Zealand",
            "Ireland", "Denmark", "Spain", "Portugal", "Finland");

    public static final List<String> COUNTRY_CODES = List.of(
            "US", "CA", "GB", "DE", "FR", "NL", "SE", "JP", "AU", "NZ",
            "IE", "DK", "ES", "PT", "FI");

    public static final List<String> LOREM = List.of(
            "audit", "batch", "cadence", "dataset", "edge", "fixture", "gateway",
            "handler", "index", "journal", "kernel", "ledger", "manifest", "node",
            "outbox", "partition", "queue", "replica", "shard", "throttle", "upsert",
            "vector", "window", "yield", "zone", "cursor", "digest", "envelope",
            "fanout", "gauge", "histogram", "idempotent", "jitter", "keyspace");

    public static final List<String> STATUSES = List.of(
            "PENDING", "ACTIVE", "COMPLETED", "CANCELLED", "FAILED", "ARCHIVED");

    /**
     * IBAN body lengths per country, used to keep a masked IBAN the right size.
     * Only the countries in COUNTRY_CODES need entries.
     */
    public static int ibanBbanLength(String countryCode) {
        return switch (countryCode) {
            case "GB", "IE" -> 18;
            case "DE" -> 18;
            case "FR" -> 23;
            case "NL" -> 14;
            case "SE" -> 20;
            case "DK", "FI" -> 14;
            case "ES", "PT" -> 21;
            default -> 20;
        };
    }

    private Corpora() {
    }

    /** Deterministic pick from a list. */
    public static String pick(List<String> corpus, long index) {
        return corpus.get((int) Math.floorMod(index, corpus.size()));
    }
}
