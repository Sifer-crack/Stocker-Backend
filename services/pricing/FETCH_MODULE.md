# Fetch Module — Under Price Engine

Web crawling module for the pricing service. Fetches real-time product and price
data from NZ supermarkets and culture stores using third-party API providers.

## Architecture

```
com.stocker.pricing.fetch/
├── model/
│   ├── RawProduct.java              # Normalized crawled product
│   └── WebFetchRequest.java         # Query object (search term, filters)
├── WebFetcher.java                  # Interface (strategy pattern)
├── WebFetcherProvider.java          # Enum: SPREAD | SERPAPI | SCRAPINGBEE | SCRAPERAPI | RAPIDAPI
├── impl/
│   ├── SpreadWebFetcher.java        # NZ-native (Pak'nSave, New World, Woolworths)
│   ├── SerpApiWebFetcher.java       # Google Shopping NZ
│   ├── ScrapingBeeWebFetcher.java   # JS-rendered scraping + Google Shopping
│   ├── ScraperApiWebFetcher.java    # Proxy-based scraping with anti-bot
│   └── RapidApiWebFetcher.java      # RapidAPI host (configurable; Woolworths NZ default)
├── config/
│   ├── WebFetcherProperties.java    # @ConfigurationProperties("app.fetch")
│   ├── WebFetcherConfig.java        # Bean selection via @ConditionalOnProperty
│   └── WebClientConfig.java         # WebClient with timeouts
└── FetchModuleConfig.java           # Top-level config import
```

## Wiring

The module is exercised end-to-end via the gRPC `Search` RPC
(`SearchRequest { search_term, item_id, store_urls, category }`):

1. `api/grpc/PriceRecordGrpcController.search` validates `item_id` and delegates to
   `service/PriceSearchService`.
2. `PriceSearchService` builds a `WebFetchRequest` and calls the active `WebFetcher`.
3. Crawled `RawProduct`s are mapped to `PriceRecord`s by `service/RawProductPriceRecordMapper`
   (crawled results are `channel = pickup`, `currency = NZD`, `store_id = store URL`).
4. Mapped records are persisted to `price_records` and returned in `SearchResponse`.

Provider selection is unchanged: `app.fetch.provider` / `STOCKER_FETCH_PROVIDER` decides which
`WebFetcher` bean is active. All HTTP calls share the `WebClient` from `WebClientConfig`, which now
honours `app.fetch.timeout-seconds` via a Reactor Netty `responseTimeout`.

## Third-Party Providers

| Provider | Type | NZ Coverage | Cost | Default |
|---|---|---|---|---|
| **Spread** | REST JSON | Pak'nSave, New World, Woolworths | Free tier | Yes |
| **RapidAPI** | REST JSON | Woolworths (AU catalog) via `woolworths-products-api` | per-API subscription | No |
| **SerpApi** | REST JSON | Google Shopping NZ (all stores) | $25/mo+ | No |
| **ScrapingBee** | REST + HTML | Google Shopping + direct page scraping | $49/mo+ | No |
| **ScraperAPI** | REST + HTML | Proxy-based scraping, anti-bot bypass | $49/mo+ | No |

## Design

- **Strategy pattern**: `WebFetcher` interface with one active impl selected by `app.fetch.provider` env var.
- **WebClient** (non-blocking) for all HTTP calls to third-party APIs.
- **RawProduct** is the normalized output — each impl maps its provider's response to this model.
- **SpreadWebFetcher** is the default (NZ-specific, free, purpose-built for this use case).

## NZ Supermarket Chains

| Chain | Owner | Format |
|---|---|---|
| PAK'nSAVE | Foodstuffs | Warehouse, lowest prices |
| New World | Foodstuffs | Full-service supermarket |
| Four Square | Foodstuffs | Neighbourhood convenience |
| Woolworths NZ | Woolworths Group | Full-service (ex-Countdown) |
| SuperValue/FreshChoice | Woolworths NZ | Smaller format, franchise |

## NZ Culture Stores (Configurable)

| Store | Type |
|---|---|
| Tai Ping | Asian supermarket |
| Lim Chhour | Asian grocery |
| Goldenresult | Asian grocery |

Additional culture stores can be added via `app.fetch.culture-stores` in `application.yml`.

## Configuration

```yaml
app:
  fetch:
    provider: ${STOCKER_FETCH_PROVIDER:spread}
    timeout-seconds: 30
    spread:
      base-url: ${STOCKER_SPREAD_BASE_URL:https://spread.butterup.app/spread-api/v1}
      api-key: ${STOCKER_SPREAD_API_KEY:}
    serpapi:
      base-url: https://serpapi.com/search
      api-key: ${STOCKER_SERPAPI_KEY:}
      country: nz
    scrapingbee:
      base-url: https://app.scrapingbee.com/api/v1
      api-key: ${STOCKER_SCRAPINGBEE_KEY:}
    scraperapi:
      base-url: https://api.scraperapi.com
      api-key: ${STOCKER_SCRAPERAPI_KEY:}
    rapidapi:
      base-url: ${STOCKER_RAPIDAPI_BASE_URL:}
      host: ${STOCKER_RAPIDAPI_HOST:}
      endpoint: ${STOCKER_RAPIDAPI_ENDPOINT:/products/search}
      api-key: ${STOCKER_RAPIDAPI_KEY:}
      query-param: q
      limit: 50
    culture-stores:
      - name: Tai Ping
        url: https://www.taiping.co.nz
        chain: taiping
      - name: Lim Chhour
        url: https://www.limchhour.co.nz
        chain: limchhour
      - name: Goldenresult
        url: https://www.goldenresult.co.nz
        chain: goldenresult
```

## Environment Variables

```bash
STOCKER_FETCH_PROVIDER=spread          # spread | rapidapi | serpapi | scrapingbee | scraperapi
STOCKER_SPREAD_API_KEY=your-key
STOCKER_RAPIDAPI_KEY=your-key
STOCKER_RAPIDAPI_HOST=your-api.p.rapidapi.com
STOCKER_RAPIDAPI_BASE_URL=https://your-api.p.rapidapi.com
STOCKER_RAPIDAPI_ENDPOINT=/products/search
STOCKER_SERPAPI_KEY=your-key
STOCKER_SCRAPINGBEE_KEY=your-key
STOCKER_SCRAPERAPI_KEY=your-key
```

## RapidAPI provider notes

- Authenticates with the standard `x-rapidapi-key` / `x-rapidapi-host` headers.
- The listing host is the only thing that pins the marketplace API. Point
  `STOCKER_RAPIDAPI_HOST` (and matching `STOCKER_RAPIDAPI_BASE_URL`) at the specific
  RapidAPI catalog entry you subscribe to — e.g. a Woolworths NZ product-search API.
- `chainId` is derived from the host: hosts containing `woolworths`/`countdown`
  resolve to `woolworths`; otherwise the dotted host's first label (non-alphanumerics
  stripped) is used.
- Response parsing is tolerant: root array or `products`/`items`/`results`/`data`/
  `records` nodes; common name/price/url/image field aliases are handled.
- Live verification is gated on `STOCKER_RAPIDAPI_KEY` + host being set; the active
  provider is selected with `STOCKER_FETCH_PROVIDER=rapidapi`.

## Dependencies

- `spring-boot-starter-webflux` — WebClient for non-blocking HTTP

## Verification

```bash
./gradlew :services:pricing:compileJava
./gradlew :services:pricing:build
```

Live scraper test (Woolworths NZ, via the active provider) — persists crawled prices to
`price_records`:

```bash
# Set STOCKER_SPREAD_API_KEY in the repo-root .env first; the test is skipped otherwise.
./gradlew :services:pricing:test --tests '*WoolworthsSpreadIntegrationTest*'
```
