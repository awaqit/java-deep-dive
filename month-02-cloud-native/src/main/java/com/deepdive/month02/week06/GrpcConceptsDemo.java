package com.deepdive.month02.week06;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Week 6: gRPC Concepts
 *
 * CONCEPT: gRPC is a high-performance RPC framework by Google.
 * It uses HTTP/2 for transport and Protocol Buffers (protobuf) for serialization.
 *
 * gRPC vs REST:
 * ┌──────────────┬─────────────────────────────┬────────────────────────────┐
 * │ Feature      │ gRPC                        │ REST/JSON                  │
 * ├──────────────┼─────────────────────────────┼────────────────────────────┤
 * │ Protocol     │ HTTP/2                      │ HTTP/1.1 or HTTP/2         │
 * │ Serialization│ Protobuf (binary, ~5x faster)│ JSON (text, human-readable)│
 * │ Type safety  │ Strong (proto schema)       │ Weak (JSON validation)     │
 * │ Streaming    │ Native (4 types)            │ SSE/WebSocket (manual)     │
 * │ Code gen     │ From .proto files           │ OpenAPI/Swagger            │
 * │ Browser      │ Limited (grpc-web needed)   │ Native                     │
 * │ Debugging    │ Harder (binary protocol)    │ Easy (curl, browser)       │
 * │ Best for     │ Internal services, IoT, ML  │ Public APIs, browser       │
 * └──────────────┴─────────────────────────────┴────────────────────────────┘
 *
 * gRPC Call Types:
 * 1. Unary:              One request, one response (like traditional RPC)
 * 2. Server Streaming:   One request, many responses (server pushes data stream)
 * 3. Client Streaming:   Many requests, one response (client uploads data stream)
 * 4. Bidirectional:      Many requests, many responses (full duplex)
 *
 * Protocol Buffers (.proto file example):
 * ----------------------------------------
 * syntax = "proto3";
 * package product;
 *
 * message Product {
 *   string id = 1;
 *   string name = 2;
 *   double price = 3;
 *   ProductCategory category = 4;
 * }
 *
 * enum ProductCategory {
 *   UNKNOWN = 0;
 *   ELECTRONICS = 1;
 *   CLOTHING = 2;
 * }
 *
 * service ProductService {
 *   rpc GetProduct(GetProductRequest) returns (Product);              // Unary
 *   rpc ListProducts(ListRequest) returns (stream Product);           // Server stream
 *   rpc BulkCreate(stream Product) returns (BulkCreateResponse);     // Client stream
 *   rpc ProductChat(stream ChatMessage) returns (stream ChatMessage); // Bidirectional
 * }
 * ----------------------------------------
 *
 * NOTE: Full gRPC requires the gRPC dependencies (see month-02 build.gradle).
 * This demo shows the concepts and patterns in pure Java.
 */
public class GrpcConceptsDemo {

    // ==================== DOMAIN TYPES (would be generated from .proto) ====================

    record Product(String id, String name, double price, String category) {}
    record GetProductRequest(String productId) {}
    record ListProductsRequest(String category, int maxResults) {}
    record BulkCreateRequest(List<Product> products) {}
    record BulkCreateResponse(int created, int failed, List<String> errors) {}

    // CONCEPT: Simulating gRPC status codes
    enum GrpcStatus {
        OK, NOT_FOUND, INVALID_ARGUMENT, INTERNAL, UNAVAILABLE, PERMISSION_DENIED
    }

    record GrpcResponse<T>(GrpcStatus status, T data, String message) {
        static <T> GrpcResponse<T> ok(T data) { return new GrpcResponse<>(GrpcStatus.OK, data, null); }
        static <T> GrpcResponse<T> notFound(String msg) { return new GrpcResponse<>(GrpcStatus.NOT_FOUND, null, msg); }
        static <T> GrpcResponse<T> error(GrpcStatus status, String msg) { return new GrpcResponse<>(status, null, msg); }
    }

    // ==================== 1. UNARY RPC ====================
    // CONCEPT: One request, one response. Simplest form of gRPC.
    // Equivalent to a traditional function call or REST GET.

    static class ProductServiceUnary {
        private static final Map<String, Product> DB = new HashMap<>(Map.of(
                "P001", new Product("P001", "Laptop Pro", 1299.99, "ELECTRONICS"),
                "P002", new Product("P002", "Wireless Mouse", 49.99, "ELECTRONICS"),
                "P003", new Product("P003", "Standing Desk", 599.99, "FURNITURE")
        ));

        // Unary RPC method
        GrpcResponse<Product> getProduct(GetProductRequest request) {
            // CONCEPT: In real gRPC, this method signature is generated from proto
            // The framework handles serialization/deserialization and HTTP/2 framing
            Product product = DB.get(request.productId());
            if (product == null) {
                return GrpcResponse.notFound("Product not found: " + request.productId());
            }
            return GrpcResponse.ok(product);
        }
    }

    // ==================== 2. SERVER STREAMING ====================
    // CONCEPT: Client sends one request, server streams multiple responses.
    // Use case: Live feeds, large datasets that would timeout if sent all at once,
    //           real-time price updates, log tailing.

    static class ProductServiceServerStream {
        private static final List<Product> CATALOG = List.of(
                new Product("P001", "Laptop Pro", 1299.99, "ELECTRONICS"),
                new Product("P002", "Wireless Mouse", 49.99, "ELECTRONICS"),
                new Product("P003", "Keyboard", 149.99, "ELECTRONICS"),
                new Product("P004", "Standing Desk", 599.99, "FURNITURE"),
                new Product("P005", "Office Chair", 399.99, "FURNITURE")
        );

        // Server streaming: returns results via StreamObserver (callback)
        // In real gRPC: void listProducts(request, StreamObserver<Product> responseObserver)
        void listProducts(ListProductsRequest request, Consumer<Product> responseObserver,
                          Runnable onComplete, Consumer<Throwable> onError) {
            // CONCEPT: Each onNext() call sends one message over the stream
            CompletableFuture.runAsync(() -> {
                try {
                    CATALOG.stream()
                            .filter(p -> request.category() == null ||
                                    p.category().equals(request.category()))
                            .limit(request.maxResults())
                            .forEach(p -> {
                                responseObserver.accept(p); // Simulate stream message
                                simulateNetworkLatency();
                            });
                    onComplete.run(); // Signal stream completion
                } catch (Exception e) {
                    onError.accept(e);
                }
            });
        }

        private void simulateNetworkLatency() {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ==================== 3. CLIENT STREAMING ====================
    // CONCEPT: Client sends multiple messages, server sends one response.
    // Use case: File upload, bulk data ingestion, batch processing.

    static class ProductServiceClientStream {
        // Client streaming: returns a StreamObserver for the client to send messages
        // In real gRPC: StreamObserver<Product> bulkCreate(StreamObserver<BulkCreateResponse> responseObserver)

        static class BulkCreateStream {
            private final List<Product> received = new ArrayList<>();
            private final CompletableFuture<BulkCreateResponse> result = new CompletableFuture<>();

            void onNext(Product product) {
                // Validate and collect each message
                if (product.name() == null || product.name().isEmpty()) {
                    // In real gRPC: call responseObserver.onError() to abort
                    System.out.println("  STREAM: Rejected invalid product (no name)");
                    return;
                }
                received.add(product);
                System.out.println("  STREAM: Received product: " + product.name());
            }

            void onCompleted() {
                // Client signals "done sending" - server now processes all received data
                int created = received.size();
                result.complete(new BulkCreateResponse(created, 0, Collections.emptyList()));
            }

            CompletableFuture<BulkCreateResponse> getResult() { return result; }
        }
    }

    // ==================== 4. gRPC INTERCEPTORS ====================
    // CONCEPT: Cross-cutting concerns (auth, logging, metrics) via interceptors.
    // Similar to Servlet filters or Spring AOP for REST.

    @FunctionalInterface
    interface GrpcInterceptor<REQ, RESP> {
        GrpcResponse<RESP> intercept(REQ request, GrpcHandler<REQ, RESP> next);

        @FunctionalInterface
        interface GrpcHandler<REQ, RESP> {
            GrpcResponse<RESP> handle(REQ request);
        }
    }

    // Logging interceptor
    static <REQ, RESP> GrpcInterceptor<REQ, RESP> loggingInterceptor() {
        return (request, next) -> {
            System.out.println("  [INTERCEPTOR] Request: " + request.getClass().getSimpleName());
            long start = System.currentTimeMillis();
            GrpcResponse<RESP> response = next.handle(request);
            System.out.printf("  [INTERCEPTOR] Response: %s in %dms%n",
                    response.status(), System.currentTimeMillis() - start);
            return response;
        };
    }

    // Auth interceptor
    static <REQ, RESP> GrpcInterceptor<REQ, RESP> authInterceptor(String validToken) {
        return (request, next) -> {
            // In real gRPC: read from Metadata (headers)
            String token = "bearer valid-token"; // Simulate header reading
            if (!token.contains(validToken)) {
                return GrpcResponse.error(GrpcStatus.PERMISSION_DENIED, "Invalid token");
            }
            return next.handle(request);
        };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== gRPC Concepts Demo ===");

        demonstrateUnaryRpc();
        demonstrateServerStreaming();
        demonstrateClientStreaming();
        demonstrateInterceptors();
        explainProtobufBenefits();
    }

    private static void demonstrateUnaryRpc() {
        System.out.println("\n--- 1. Unary RPC ---");
        ProductServiceUnary service = new ProductServiceUnary();

        GrpcResponse<Product> found = service.getProduct(new GetProductRequest("P001"));
        System.out.println("Request: GetProduct(P001)");
        System.out.printf("Response: status=%s, product=%s%n", found.status(), found.data());

        GrpcResponse<Product> notFound = service.getProduct(new GetProductRequest("INVALID"));
        System.out.println("Request: GetProduct(INVALID)");
        System.out.printf("Response: status=%s, message=%s%n", notFound.status(), notFound.message());
    }

    private static void demonstrateServerStreaming() throws Exception {
        System.out.println("\n--- 2. Server Streaming ---");
        ProductServiceServerStream service = new ProductServiceServerStream();

        List<Product> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        System.out.println("Request: ListProducts(ELECTRONICS, max=3)");
        service.listProducts(
                new ListProductsRequest("ELECTRONICS", 3),
                p -> { System.out.println("  Stream received: " + p.name()); received.add(p); },
                () -> { System.out.println("  Stream completed"); latch.countDown(); },
                e -> { System.err.println("  Stream error: " + e.getMessage()); latch.countDown(); }
        );

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("Total products received via stream: " + received.size());
    }

    private static void demonstrateClientStreaming() throws Exception {
        System.out.println("\n--- 3. Client Streaming ---");
        ProductServiceClientStream.BulkCreateStream stream = new ProductServiceClientStream.BulkCreateStream();

        // Client sends multiple messages
        stream.onNext(new Product("NEW-1", "Webcam 4K", 79.99, "ELECTRONICS"));
        stream.onNext(new Product("NEW-2", "USB Hub", 39.99, "ELECTRONICS"));
        stream.onNext(new Product("NEW-3", "", 9.99, "ACCESSORIES")); // Invalid - no name
        stream.onNext(new Product("NEW-4", "Desk Lamp", 59.99, "FURNITURE"));
        stream.onCompleted(); // Signal end of stream

        BulkCreateResponse response = stream.getResult().get(1, TimeUnit.SECONDS);
        System.out.println("Bulk create response: created=" + response.created() + " failed=" + response.failed());
    }

    private static void demonstrateInterceptors() {
        System.out.println("\n--- 4. Interceptors (Middleware Chain) ---");
        ProductServiceUnary service = new ProductServiceUnary();

        // Chain interceptors: auth -> logging -> actual handler
        GrpcInterceptor<GetProductRequest, Product> loggingInt = loggingInterceptor();
        GrpcInterceptor<GetProductRequest, Product> authInt = authInterceptor("valid-token");

        // Apply interceptors
        GrpcResponse<Product> response = authInt.intercept(
                new GetProductRequest("P002"),
                req -> loggingInt.intercept(req, service::getProduct)
        );
        System.out.println("Final response: " + (response.data() != null ? response.data().name() : response.message()));
    }

    private static void explainProtobufBenefits() {
        System.out.println("\n--- Protocol Buffers vs JSON ---");
        System.out.println("Example Product object serialization:");
        System.out.println("  JSON:     {\"id\":\"P001\",\"name\":\"Laptop Pro\",\"price\":1299.99,\"category\":\"ELECTRONICS\"}");
        System.out.println("  Protobuf: binary, ~5x smaller, ~6x faster to serialize");
        System.out.println();
        System.out.println("Protobuf field evolution (backward/forward compatible):");
        System.out.println("  1. Add new fields (old clients ignore them)");
        System.out.println("  2. Remove fields (use 'reserved' keyword to prevent reuse)");
        System.out.println("  3. NEVER change field numbers (breaks binary compatibility)");
        System.out.println();
        System.out.println("gRPC use cases at Staff Engineer level:");
        System.out.println("  - Inter-service communication in microservices");
        System.out.println("  - ML model serving (TensorFlow Serving uses gRPC)");
        System.out.println("  - Kubernetes API server uses gRPC internally");
        System.out.println("  - IoT device communication (binary efficiency matters)");
    }
}
