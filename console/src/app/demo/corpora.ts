/**
 * The word lists masked and generated values are drawn from.
 *
 * Mirrors the service's corpora. Fixed and committed rather than generated: a
 * masked value has to be reproducible across reloads and versions, which rules
 * out anything derived from a library's internal ordering. Adding is safe;
 * reordering or removing changes every previously produced value.
 *
 * Email domains are all RFC 2606 reserved names, so a generated address can
 * never resolve to a real mail server.
 */
export const CORPORA = {
  emailDomains: [
    'example.com', 'example.net', 'example.org',
    'mail.example.com', 'corp.example.com', 'test.example.com',
    'inbox.example.net', 'users.example.org', 'accounts.example.com',
    'notifications.example.net', 'billing.example.org', 'support.example.com',
  ],

  givenNames: [
    'Avery', 'Blake', 'Cameron', 'Devon', 'Emerson', 'Finley', 'Gray', 'Harper',
    'Indigo', 'Jordan', 'Kai', 'Logan', 'Morgan', 'Noel', 'Oakley', 'Parker',
    'Quinn', 'Reese', 'Sawyer', 'Tatum', 'Uriel', 'Vale', 'Wren', 'Xen',
    'Yael', 'Zion', 'Adair', 'Brett', 'Casey', 'Dallas', 'Ellis', 'Frankie',
    'Greer', 'Hayden', 'Ira', 'Jamie', 'Kendall', 'Lane', 'Marlowe', 'Nico',
    'Ocean', 'Peyton', 'Rory', 'Sage', 'Toby', 'Umber', 'Vesper', 'Winter',
  ],

  familyNames: [
    'Ashford', 'Blackwood', 'Castellan', 'Draycott', 'Ellsworth', 'Fairbairn',
    'Grantley', 'Hollowell', 'Ironside', 'Jessamine', 'Kestrel', 'Lockhart',
    'Marchetti', 'Northcote', 'Oakhurst', 'Pemberton', 'Quillon', 'Ravenscroft',
    'Sterling', 'Thornbury', 'Underhill', 'Vandermeer', 'Westbrook', 'Yarrow',
    'Ziegler', 'Aldergate', 'Brightwater', 'Calloway', 'Dunmore', 'Everly',
    'Fenwick', 'Galloway', 'Harkness', 'Inglewood', 'Kingsley', 'Larkspur',
  ],

  streetTypes: [
    'Street', 'Avenue', 'Road', 'Lane', 'Way', 'Drive', 'Court', 'Place',
    'Terrace', 'Boulevard', 'Crescent', 'Walk',
  ],

  streetNames: [
    'Alder', 'Birch', 'Cedar', 'Dogwood', 'Elm', 'Fir', 'Ginkgo', 'Hawthorn',
    'Ironwood', 'Juniper', 'Katsura', 'Linden', 'Maple', 'Nyssa', 'Olive',
    'Poplar', 'Quince', 'Redwood', 'Sycamore', 'Tupelo', 'Umbrella', 'Viburnum',
    'Willow', 'Yew', 'Zelkova',
  ],

  cities: [
    'Ashbourne', 'Brackenford', 'Cliffmere', 'Dunwich Falls', 'Eastvale',
    'Fernhollow', 'Glenmarch', 'Harrowgate', 'Innisford', 'Jarrowmead',
    'Kirkstall', 'Lynnfield', 'Marchwood', 'Netherby', 'Oldcastle',
    'Pinehaven', 'Quarryside', 'Rookhaven', 'Stonebridge', 'Thornfield',
    'Upperton', 'Vinemount', 'Westmarch', 'Yarrowdale',
  ],

  regions: [
    'Northshire', 'Eastmarch', 'Southhold', 'Westreach', 'Midvale',
    'Highmoor', 'Lowfen', 'Farhaven', 'Nearcliff', 'Overton',
    'Underwood County', 'Riverbend',
  ],

  companies: [
    'Northwind Systems', 'Brightpath Labs', 'Meridian Works', 'Cobalt Analytics',
    'Ironvale Logistics', 'Quillmark Media', 'Sparrow Robotics', 'Tidewater Foods',
    'Umbra Security', 'Vantage Retail', 'Wexford Health', 'Zephyr Transit',
  ],

  jobTitles: [
    'Platform Engineer', 'Data Steward', 'Release Manager', 'Quality Analyst',
    'Site Reliability Engineer', 'Product Designer', 'Solutions Architect',
    'Technical Writer', 'Support Specialist', 'Engineering Manager',
    'Database Administrator', 'Security Analyst',
  ],

  departments: [
    'Platform', 'Data', 'Quality', 'Security', 'Operations', 'Support',
    'Design', 'Infrastructure', 'Payments', 'Identity', 'Reporting', 'Tooling',
  ],

  products: [
    'Aluminium Kettle', 'Bamboo Desk Mat', 'Ceramic Mug', 'Down Duvet',
    'Electric Grinder', 'Felt Organiser', 'Glass Carafe', 'Hemp Tote',
    'Insulated Bottle', 'Jute Runner', 'Knit Throw', 'Linen Napkins',
    'Merino Socks', 'Nylon Duffel', 'Oak Cutting Board', 'Porcelain Bowl',
    'Quilted Jacket', 'Rattan Basket', 'Steel Skillet', 'Terracotta Planter',
  ],

  currencies: ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD', 'CHF', 'SEK', 'NOK', 'NZD'],

  countries: [
    'United States', 'Canada', 'United Kingdom', 'Germany', 'France',
    'Netherlands', 'Sweden', 'Japan', 'Australia', 'New Zealand',
    'Ireland', 'Denmark', 'Spain', 'Portugal', 'Finland',
  ],

  countryCodes: ['US', 'CA', 'GB', 'DE', 'FR', 'NL', 'SE', 'JP', 'AU', 'NZ', 'IE', 'DK', 'ES', 'PT', 'FI'],

  lorem: [
    'audit', 'batch', 'cadence', 'dataset', 'edge', 'fixture', 'gateway',
    'handler', 'index', 'journal', 'kernel', 'ledger', 'manifest', 'node',
    'outbox', 'partition', 'queue', 'replica', 'shard', 'throttle', 'upsert',
    'vector', 'window', 'yield', 'zone', 'cursor', 'digest', 'envelope',
    'fanout', 'gauge', 'histogram', 'idempotent', 'jitter', 'keyspace',
  ],

  statuses: ['PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'FAILED', 'ARCHIVED'],
} as const;

/** IBAN body length per country, so a masked IBAN keeps the right size. */
export function ibanBbanLength(countryCode: string): number {
  switch (countryCode) {
    case 'GB':
    case 'IE':
    case 'DE':
      return 18;
    case 'FR':
      return 23;
    case 'NL':
    case 'DK':
    case 'FI':
      return 14;
    case 'SE':
      return 20;
    case 'ES':
    case 'PT':
      return 21;
    default:
      return 20;
  }
}

/** Deterministic pick from a list. */
export function pick<T>(corpus: readonly T[], index: number): T {
  return corpus[((index % corpus.length) + corpus.length) % corpus.length];
}
