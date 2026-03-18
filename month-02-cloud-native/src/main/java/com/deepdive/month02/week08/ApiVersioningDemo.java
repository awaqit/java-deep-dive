package com.deepdive.month02.week08;

import java.util.*;
import java.util.function.Function;

/**
 * Week 8: API Versioning Strategies
 *
 * CONCEPT: API versioning allows you to evolve your API while maintaining
 * backward compatibility with existing clients.
 *
 * The fundamental challenge: once an API is public, clients depend on it.
 * Changes that break clients are called "breaking changes".
 *
 * Breaking changes examples:
 * - Removing a field from a response
 * - Renaming a field
 * - Changing a field type (String -> Integer)
 * - Removing an endpoint
 * - Changing required/optional status of a request field
 *
 * Non-breaking changes:
 * - Adding new optional fields to responses
 * - Adding new endpoints
 * - Adding new optional request fields with defaults
 *
 * Versioning strategies:
 * 1. URI Path versioning:   /api/v1/orders  vs  /api/v2/orders
 * 2. Request header:        X-API-Version: 2
 * 3. Accept header:         Accept: application/vnd.company.api+json;version=2
 * 4. Query parameter:       /api/orders?version=2
 *
 * Staff Engineer considerations:
 * - How long to maintain old versions? (deprecation policy)
 * - How to communicate version sunset to clients?
 * - Strangler Fig pattern for migration
 * - API gateway for routing versioned traffic
 */
public class ApiVersioningDemo {

    // ==================== VERSION 1 MODELS ====================

    // CONCEPT: V1 - initial API (flat structure)
    record OrderResponseV1(String orderId, String customerName, String customerEmail,
                            double totalAmount, String status) {}

    record OrderRequestV1(String customerName, String customerEmail,
                          List<String> productIds, String paymentMethod) {}

    // ==================== VERSION 2 MODELS ====================

    // CONCEPT: V2 - improved structure (nested customer, richer data)
    record CustomerInfo(String id, String name, String email, String tier) {}
    record OrderItem(String productId, int quantity, double unitPrice) {}
    record PaymentInfo(String method, String currency, String transactionId) {}

    record OrderResponseV2(String orderId, CustomerInfo customer, List<OrderItem> items,
                            double subtotal, double tax, double totalAmount,
                            String status, String estimatedDelivery) {}

    record OrderRequestV2(String customerId, List<OrderItem> items, PaymentInfo payment,
                          String deliveryAddress, Map<String, String> metadata) {}

    // ==================== VERSION 3 MODELS ====================

    // CONCEPT: V3 - added support for subscriptions and hypermedia (HATEOAS)
    record Link(String rel, String href, String method) {}

    record OrderResponseV3(String orderId, CustomerInfo customer, List<OrderItem> items,
                            double totalAmount, String status, boolean isSubscription,
                            String subscriptionId, List<Link> links) {}

    // ==================== API VERSION ROUTER ====================

    @FunctionalInterface
    interface ApiHandler<REQ, RESP> {
        RESP handle(REQ request, ApiContext context);
    }

    record ApiContext(String apiVersion, String clientId, Map<String, String> headers) {}

    enum ApiVersion {
        V1("1.0"), V2("2.0"), V3("3.0"), LATEST("3.0");

        final String value;
        ApiVersion(String value) { this.value = value; }

        static ApiVersion fromString(String v) {
            if (v == null || v.isEmpty()) return LATEST;
            return switch (v.replaceAll("[^0-9.]", "")) {
                case "1", "1.0" -> V1;
                case "2", "2.0" -> V2;
                case "3", "3.0" -> V3;
                default -> LATEST;
            };
        }
    }

    /**
     * CONCEPT: Version router - routes requests to appropriate handler
     * based on the requested API version.
     * In production, this lives in the API Gateway or a Spring MVC controller.
     */
    static class OrderApiRouter {
        private final Map<ApiVersion, Function<ApiContext, Object>> handlers = new EnumMap<>(ApiVersion.class);

        OrderApiRouter() {
            handlers.put(ApiVersion.V1, ctx -> handleV1(ctx));
            handlers.put(ApiVersion.V2, ctx -> handleV2(ctx));
            handlers.put(ApiVersion.V3, ctx -> handleV3(ctx));
            handlers.put(ApiVersion.LATEST, ctx -> handleV3(ctx));
        }

        Object route(String version, String clientId, Map<String, String> headers) {
            ApiVersion apiVersion = ApiVersion.fromString(version);
            ApiContext context = new ApiContext(apiVersion.value, clientId, headers);

            // CONCEPT: Deprecation warning in response headers
            if (apiVersion == ApiVersion.V1) {
                headers.put("Deprecation", "true");
                headers.put("Sunset", "2025-12-31");
                headers.put("Link", "</api/v3/orders>; rel=\"successor-version\"");
                System.out.println("  [WARN] Client " + clientId + " using deprecated API v1");
            }

            return handlers.get(apiVersion).apply(context);
        }

        private OrderResponseV1 handleV1(ApiContext ctx) {
            // V1: Flat structure, limited data
            return new OrderResponseV1("ORD-001", "John Doe", "john@example.com", 99.99, "CONFIRMED");
        }

        private OrderResponseV2 handleV2(ApiContext ctx) {
            // V2: Richer nested structure
            return new OrderResponseV2(
                    "ORD-001",
                    new CustomerInfo("CUST-42", "John Doe", "john@example.com", "GOLD"),
                    List.of(new OrderItem("LAPTOP", 1, 99.99)),
                    99.99, 8.99, 108.98,
                    "CONFIRMED", "2024-01-20"
            );
        }

        private OrderResponseV3 handleV3(ApiContext ctx) {
            // V3: HATEOAS + subscription support
            return new OrderResponseV3(
                    "ORD-001",
                    new CustomerInfo("CUST-42", "John Doe", "john@example.com", "GOLD"),
                    List.of(new OrderItem("LAPTOP", 1, 99.99)),
                    108.98, "CONFIRMED", false, null,
                    List.of(
                            new Link("self", "/api/v3/orders/ORD-001", "GET"),
                            new Link("cancel", "/api/v3/orders/ORD-001/cancel", "POST"),
                            new Link("track", "/api/v3/orders/ORD-001/tracking", "GET")
                    )
            );
        }
    }

    /**
     * CONCEPT: Header-based versioning
     * Client sends: Accept: application/vnd.company.orders+json;version=2
     * or:           X-API-Version: 2
     *
     * Pros: Clean URLs, allows content negotiation
     * Cons: Less visible, harder to test (can't just paste URL in browser)
     */
    static class HeaderVersionedApi {
        Object handleRequest(Map<String, String> headers) {
            String version = headers.getOrDefault("X-API-Version",
                    extractVersionFromAccept(headers.getOrDefault("Accept", "")));
            System.out.println("  Header-based version detection: " + version);
            return switch (version) {
                case "1" -> "Response in V1 format (flat JSON)";
                case "2" -> "Response in V2 format (nested JSON with richer data)";
                default -> "Response in latest format (V3 with HATEOAS links)";
            };
        }

        private String extractVersionFromAccept(String acceptHeader) {
            // Parse: application/vnd.company.api+json;version=2
            if (acceptHeader.contains("version=")) {
                return acceptHeader.replaceAll(".*version=([0-9]+).*", "$1");
            }
            return "3"; // Default to latest
        }
    }

    /**
     * CONCEPT: Strangler Fig pattern for API migration.
     * New API version is built alongside old one.
     * Traffic gradually shifted from old to new.
     * Old version removed when client adoption is complete.
     */
    static class StranglerFigRouter {
        private double v2TrafficPercentage = 0.0; // Start: 100% to V1

        // Gradual traffic migration: 0% -> 10% -> 50% -> 100% -> sunset V1
        void migrateTraffic(String orderId) {
            // In production: use feature flags (LaunchDarkly), canary deployment, or A/B testing
            boolean routeToV2 = Math.random() < v2TrafficPercentage;
            System.out.printf("  Routing order %s to %s (%.0f%% on V2)%n",
                    orderId, routeToV2 ? "V2-NEW" : "V1-LEGACY", v2TrafficPercentage * 100);
        }

        void setV2TrafficPercentage(double percentage) {
            this.v2TrafficPercentage = percentage;
            System.out.printf("  Traffic migration: %.0f%% -> V2%n", percentage * 100);
        }
    }

    /**
     * CONCEPT: API deprecation policy - communicate sunsets clearly.
     * Best practice: provide at least 6-12 months notice before removing a version.
     */
    static class DeprecationManager {
        record VersionInfo(String version, boolean deprecated, String sunsetDate, String migrationGuide) {}

        static final List<VersionInfo> VERSIONS = List.of(
                new VersionInfo("v1", true, "2025-06-30", "/docs/migrate-v1-to-v2"),
                new VersionInfo("v2", false, null, null),
                new VersionInfo("v3", false, null, null)
        );

        void addDeprecationHeaders(String requestedVersion, Map<String, String> responseHeaders) {
            VERSIONS.stream()
                    .filter(v -> v.version().equals(requestedVersion) && v.deprecated())
                    .findFirst()
                    .ifPresent(info -> {
                        responseHeaders.put("Deprecation", "true");
                        if (info.sunsetDate() != null) {
                            responseHeaders.put("Sunset", info.sunsetDate());
                        }
                        if (info.migrationGuide() != null) {
                            responseHeaders.put("Link", info.migrationGuide() + "; rel=\"successor-version\"");
                        }
                        System.out.println("  [DEPRECATION HEADER] Version " + requestedVersion +
                                " deprecated, sunset: " + info.sunsetDate());
                    });
        }
    }

    public static void main(String[] args) {
        System.out.println("=== API Versioning Demo ===");

        demonstrateUriVersioning();
        demonstrateHeaderVersioning();
        demonstrateStranglerFig();
        demonstrateDeprecationPolicy();
        compareVersioningStrategies();
    }

    private static void demonstrateUriVersioning() {
        System.out.println("\n--- URI Path Versioning ---");
        System.out.println("GET /api/v1/orders/ORD-001");
        System.out.println("GET /api/v2/orders/ORD-001");
        System.out.println("GET /api/v3/orders/ORD-001");

        OrderApiRouter router = new OrderApiRouter();
        Map<String, String> responseHeaders = new HashMap<>();

        Object v1Response = router.route("v1", "legacy-client-A", responseHeaders);
        System.out.println("\nV1 Response: " + v1Response);
        System.out.println("Response Headers (deprecation): " + responseHeaders);

        Object v2Response = router.route("v2", "modern-client-B", new HashMap<>());
        System.out.println("\nV2 Response: " + v2Response);

        Object v3Response = router.route("v3", "new-client-C", new HashMap<>());
        System.out.println("\nV3 Response (with HATEOAS links): " + v3Response);
    }

    private static void demonstrateHeaderVersioning() {
        System.out.println("\n--- Header-Based Versioning ---");
        HeaderVersionedApi api = new HeaderVersionedApi();

        System.out.println("Request with X-API-Version: 1");
        System.out.println("  -> " + api.handleRequest(Map.of("X-API-Version", "1")));

        System.out.println("Request with Accept: application/vnd.company.api+json;version=2");
        System.out.println("  -> " + api.handleRequest(Map.of("Accept", "application/vnd.company.api+json;version=2")));

        System.out.println("Request with no version header");
        System.out.println("  -> " + api.handleRequest(Map.of("Content-Type", "application/json")));
    }

    private static void demonstrateStranglerFig() {
        System.out.println("\n--- Strangler Fig Pattern (Gradual Migration) ---");
        StranglerFigRouter router = new StranglerFigRouter();

        router.setV2TrafficPercentage(0.0);   // Week 1: All V1
        router.migrateTraffic("ORD-100");

        router.setV2TrafficPercentage(0.1);   // Week 2: 10% canary
        router.migrateTraffic("ORD-101");
        router.migrateTraffic("ORD-102");

        router.setV2TrafficPercentage(0.5);   // Week 4: 50% migration
        router.migrateTraffic("ORD-103");

        router.setV2TrafficPercentage(1.0);   // Week 8: Full migration
        router.migrateTraffic("ORD-104");
        System.out.println("  V1 legacy code can now be deleted safely");
    }

    private static void demonstrateDeprecationPolicy() {
        System.out.println("\n--- Deprecation Policy ---");
        DeprecationManager deprecationManager = new DeprecationManager();

        Map<String, String> headers = new HashMap<>();
        deprecationManager.addDeprecationHeaders("v1", headers);
        System.out.println("Response headers for v1 request: " + headers);

        headers.clear();
        deprecationManager.addDeprecationHeaders("v2", headers);
        System.out.println("Response headers for v2 request: " + (headers.isEmpty() ? "(none - not deprecated)" : headers));
    }

    private static void compareVersioningStrategies() {
        System.out.println("\n--- Versioning Strategy Comparison ---");
        System.out.printf("%-25s %-12s %-12s %-15s %-20s%n",
                "Strategy", "URL Clean", "Cacheable", "Browser Friendly", "Discoverability");
        System.out.println("-".repeat(85));
        System.out.printf("%-25s %-12s %-12s %-15s %-20s%n", "URI path (/v1/orders)", "No", "Yes", "Yes", "Excellent");
        System.out.printf("%-25s %-12s %-12s %-15s %-20s%n", "X-API-Version header", "Yes", "No", "Hard", "Poor");
        System.out.printf("%-25s %-12s %-12s %-15s %-20s%n", "Accept header", "Yes", "Varies", "Hard", "Medium");
        System.out.printf("%-25s %-12s %-12s %-15s %-20s%n", "Query param (?v=1)", "No", "Yes", "Yes", "Good");
        System.out.println();
        System.out.println("Recommendation: URI path versioning for public APIs");
        System.out.println("               Header versioning for internal services with API gateway");
    }
}
