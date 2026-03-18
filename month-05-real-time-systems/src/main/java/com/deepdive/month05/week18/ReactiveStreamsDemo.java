package com.deepdive.month05.week18;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Week 18: Reactive Streams - Project Reactor Patterns
 *
 * CONCEPT: Reactive Programming is a programming paradigm based on asynchronous
 * data streams and the propagation of changes.
 *
 * Reactive Streams Specification (Publisher-Subscriber):
 * - Publisher<T>:  Source that produces data (0..N items, then complete or error)
 * - Subscriber<T>: Consumer that processes data
 * - Subscription:  Connection between Publisher and Subscriber (controls demand)
 * - Processor<T,R>: Both Publisher and Subscriber (transformer in the middle)
 *
 * Project Reactor (Spring WebFlux uses this):
 * - Mono<T>:   0 or 1 item (like CompletableFuture<Optional<T>>)
 * - Flux<T>:   0 to N items (stream of data)
 *
 * Operators (partial list):
 * - map, flatMap, filter:       Standard transforms
 * - zip, merge, concat:         Combine streams
 * - buffer, window:             Batch items
 * - retry, retryWhen:           Error recovery
 * - onErrorReturn, onErrorResume: Fallbacks
 * - subscribeOn, publishOn:     Control threading
 * - timeout:                    Add timeouts
 * - cache:                      Hot multicasting
 * - delayElements:              Time-based delays
 *
 * Cold vs Hot publishers:
 * - Cold: Each subscriber gets its own copy of the data (like a file on disk)
 * - Hot:  All subscribers share the same live data (like a live TV broadcast)
 *
 * NOTE: Full Project Reactor requires reactor-core dependency (see month-05/build.gradle).
 * This demo shows the patterns using simulated implementations.
 */
public class ReactiveStreamsDemo {

    // ==================== SIMPLIFIED REACTIVE TYPES ====================

    // Simplified Mono<T> simulation
    static class Mono<T> {
        private final Supplier<T> supplier;
        private final Throwable error;

        private Mono(Supplier<T> supplier, Throwable error) {
            this.supplier = supplier;
            this.error = error;
        }

        static <T> Mono<T> just(T value) {
            return new Mono<>(() -> value, null);
        }

        static <T> Mono<T> error(Throwable e) {
            return new Mono<>(null, e);
        }

        static <T> Mono<T> fromCallable(Callable<T> callable) {
            return new Mono<>(() -> {
                try { return callable.call(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }, null);
        }

        static <T> Mono<T> empty() {
            return new Mono<>(() -> null, null);
        }

        <R> Mono<R> map(Function<T, R> mapper) {
            if (error != null) return Mono.error(error);
            return Mono.fromCallable(() -> mapper.apply(supplier.get()));
        }

        <R> Mono<R> flatMap(Function<T, Mono<R>> mapper) {
            if (error != null) return Mono.error(error);
            try {
                T value = supplier.get();
                return value != null ? mapper.apply(value) : Mono.empty();
            } catch (Exception e) {
                return Mono.error(e);
            }
        }

        Mono<T> onErrorReturn(T fallback) {
            if (error == null) return this;
            return Mono.just(fallback);
        }

        Mono<T> onErrorResume(Function<Throwable, Mono<T>> fallback) {
            if (error == null) return this;
            return fallback.apply(error);
        }

        Optional<T> block() {
            if (error != null) throw new RuntimeException(error);
            T result = supplier.get();
            return Optional.ofNullable(result);
        }

        CompletableFuture<T> toFuture() {
            return CompletableFuture.supplyAsync(() -> {
                if (error != null) throw new RuntimeException(error);
                return supplier.get();
            });
        }

        @Override public String toString() {
            return error != null ? "Mono[error:" + error.getMessage() + "]" : "Mono[value]";
        }
    }

    // Simplified Flux<T> simulation
    static class Flux<T> {
        private final List<T> items;
        private final Throwable error;

        private Flux(List<T> items, Throwable error) {
            this.items = new ArrayList<>(items != null ? items : Collections.emptyList());
            this.error = error;
        }

        static <T> Flux<T> just(T... items) {
            return new Flux<>(Arrays.asList(items), null);
        }

        static <T> Flux<T> fromIterable(Iterable<T> iterable) {
            List<T> list = new ArrayList<>();
            iterable.forEach(list::add);
            return new Flux<>(list, null);
        }

        static <T> Flux<T> error(Throwable e) {
            return new Flux<>(null, e);
        }

        <R> Flux<R> map(Function<T, R> mapper) {
            if (error != null) return Flux.error(error);
            List<R> mapped = items.stream().map(mapper).toList();
            return new Flux<>(mapped, null);
        }

        <R> Flux<R> flatMap(Function<T, Flux<R>> mapper) {
            if (error != null) return Flux.error(error);
            List<R> result = new ArrayList<>();
            for (T item : items) {
                Flux<R> subFlux = mapper.apply(item);
                result.addAll(subFlux.items);
            }
            return new Flux<>(result, null);
        }

        Flux<T> filter(Predicate<T> predicate) {
            if (error != null) return this;
            return new Flux<>(items.stream().filter(predicate).toList(), null);
        }

        <R> Flux<R> buffer(int size, Function<List<T>, R> batchProcessor) {
            List<R> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i += size) {
                List<T> batch = items.subList(i, Math.min(i + size, items.size()));
                result.add(batchProcessor.apply(batch));
            }
            return new Flux<>(result, null);
        }

        Mono<T> reduce(BinaryOperator<T> accumulator) {
            return Mono.fromCallable(() -> items.stream().reduce(accumulator).orElse(null));
        }

        List<T> collectList() {
            if (error != null) throw new RuntimeException(error);
            return Collections.unmodifiableList(items);
        }

        void subscribe(Consumer<T> onNext, Consumer<Throwable> onError, Runnable onComplete) {
            if (error != null) { onError.accept(error); return; }
            items.forEach(onNext);
            onComplete.run();
        }

        Flux<T> doOnNext(Consumer<T> action) {
            if (error != null) return this;
            List<T> processed = new ArrayList<>();
            for (T item : items) { action.accept(item); processed.add(item); }
            return new Flux<>(processed, null);
        }

        Flux<T> take(int n) {
            return new Flux<>(items.stream().limit(n).toList(), null);
        }

        int count() { return items.size(); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Reactive Streams Demo (Simulated Project Reactor) ===");

        demonstrateMonoOperations();
        demonstrateFluxOperations();
        demonstrateErrorHandling();
        demonstrateCompositionPatterns();
        explainRealReactorPatterns();
    }

    private static void demonstrateMonoOperations() {
        System.out.println("\n--- Mono Operations (0 or 1 item) ---");

        // CONCEPT: Mono represents a single async value
        Mono<String> userName = Mono.just("alice");
        Mono<Integer> nameLength = userName.map(String::length);
        System.out.println("Username: " + userName.block().orElse("empty"));
        System.out.println("Name length: " + nameLength.block().orElse(0));

        // CONCEPT: flatMap for async chaining (like thenCompose in CompletableFuture)
        Mono<String> enriched = userName
                .flatMap(name -> Mono.just("User profile: " + name.toUpperCase()))
                .flatMap(profile -> Mono.just(profile + " [VERIFIED]"));
        System.out.println("Enriched: " + enriched.block().orElse(""));

        // CONCEPT: Empty Mono handling
        Mono<String> maybeNull = Mono.empty();
        String result = maybeNull.onErrorReturn("default-value").block().orElse("truly-empty");
        System.out.println("Empty Mono result: " + result);
    }

    private static void demonstrateFluxOperations() {
        System.out.println("\n--- Flux Operations (0 to N items) ---");

        Flux<Integer> numbers = Flux.fromIterable(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));

        // CONCEPT: Standard stream-like operations
        List<Integer> evenDoubled = numbers
                .filter(n -> n % 2 == 0)
                .map(n -> n * 2)
                .collectList();
        System.out.println("Even numbers doubled: " + evenDoubled);

        // CONCEPT: flatMap - each item produces a sub-stream
        Flux<String> expanded = Flux.just("a", "b", "c")
                .flatMap(letter -> Flux.just(letter + "1", letter + "2"));
        System.out.println("FlatMap result: " + expanded.collectList());

        // CONCEPT: buffer - group into batches
        Flux<String> batchResults = Flux.fromIterable(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
                .buffer(3, batch -> "Batch" + batch);
        System.out.println("Buffered batches: " + batchResults.collectList());

        // CONCEPT: doOnNext for side effects (logging, metrics) without changing stream
        System.out.println("Stream with side effects:");
        numbers.take(3)
                .doOnNext(n -> System.out.println("  Processing: " + n))
                .map(n -> n * n)
                .subscribe(
                        n -> System.out.println("  Result: " + n),
                        e -> System.err.println("  Error: " + e),
                        () -> System.out.println("  Stream complete!")
                );
    }

    private static void demonstrateErrorHandling() {
        System.out.println("\n--- Error Handling Patterns ---");

        // CONCEPT: onErrorReturn - return fallback value on error
        Mono<Integer> safeDivide = Mono.fromCallable(() -> 10 / 0)  // ArithmeticException
                .onErrorReturn(-1);
        System.out.println("Division by zero (onErrorReturn): " + safeDivide.block().orElse(0));

        // CONCEPT: onErrorResume - recover with a different Mono/Flux
        Mono<String> withFallback = Mono.<String>error(new RuntimeException("Primary failed"))
                .onErrorResume(e -> {
                    System.out.println("  Error: " + e.getMessage() + " -> using fallback");
                    return Mono.just("Cached fallback value");
                });
        System.out.println("With fallback: " + withFallback.block().orElse(""));

        // CONCEPT: Error in the middle of a Flux
        Flux<Integer> riskyStream = Flux.just(1, 2, 3, 4, 5);
        riskyStream
                .map(n -> {
                    if (n == 3) throw new RuntimeException("Error at 3");
                    return n;
                })
                .subscribe(
                        n -> System.out.println("  Got: " + n),
                        e -> System.out.println("  Stream terminated with error at: " + e.getMessage()),
                        () -> System.out.println("  Stream completed normally")
                );
    }

    private static void demonstrateCompositionPatterns() throws Exception {
        System.out.println("\n--- Reactive Composition Patterns ---");

        // CONCEPT: Parallel async operations with zip
        CompletableFuture<String> userFuture = CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "User: Alice";
        });
        CompletableFuture<String> orderFuture = CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "Orders: 5";
        });

        // Both execute in parallel, result combines when both complete
        String combined = userFuture
                .thenCombine(orderFuture, (user, orders) -> user + " | " + orders)
                .get(1, TimeUnit.SECONDS);
        System.out.println("Parallel fetch (zip equivalent): " + combined);

        // CONCEPT: Reactive pipeline
        System.out.println("\nReactive pipeline: events -> filter -> enrich -> aggregate");
        List<String> events = List.of("LOGIN:user-A", "PURCHASE:user-B", "LOGIN:user-C",
                "PURCHASE:user-A", "LOGOUT:user-B", "PURCHASE:user-C");

        Map<String, Long> purchasesByUser = new LinkedHashMap<>();
        Flux.fromIterable(events)
                .filter(e -> e.startsWith("PURCHASE"))
                .map(e -> e.split(":")[1])
                .subscribe(
                        user -> purchasesByUser.merge(user, 1L, Long::sum),
                        e -> System.err.println("Error: " + e),
                        () -> System.out.println("Purchase counts: " + purchasesByUser)
                );
    }

    private static void explainRealReactorPatterns() {
        System.out.println("\n--- Real Project Reactor Code Patterns ---");
        System.out.println("// Non-blocking HTTP client with WebClient:");
        System.out.println("Flux<Order> orders = webClient");
        System.out.println("    .get()");
        System.out.println("    .uri(\"/api/orders?userId={id}\", userId)");
        System.out.println("    .retrieve()");
        System.out.println("    .bodyToFlux(Order.class)");
        System.out.println("    .filter(order -> order.status().equals(\"ACTIVE\"))");
        System.out.println("    .timeout(Duration.ofSeconds(5))");
        System.out.println("    .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))");
        System.out.println("    .onErrorResume(e -> getCachedOrders(userId));");
        System.out.println();
        System.out.println("// Parallel calls with zip:");
        System.out.println("Mono.zip(");
        System.out.println("    userService.getUser(userId),");
        System.out.println("    orderService.getOrders(userId),");
        System.out.println("    inventoryService.checkStock(productId)");
        System.out.println(").map(tuple -> new DashboardView(tuple.getT1(), tuple.getT2(), tuple.getT3()))");
        System.out.println();
        System.out.println("// subscribeOn vs publishOn:");
        System.out.println("// subscribeOn: changes where subscription runs (affects source)");
        System.out.println("// publishOn:   changes where downstream processing runs");
        System.out.println("flux");
        System.out.println("    .subscribeOn(Schedulers.boundedElastic()) // I/O source on boundedElastic");
        System.out.println("    .publishOn(Schedulers.parallel())         // Processing on parallel pool");
        System.out.println("    .map(this::processData)");
    }
}
