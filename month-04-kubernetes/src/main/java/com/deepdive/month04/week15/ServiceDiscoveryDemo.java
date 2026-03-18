package com.deepdive.month04.week15;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 15: Service Discovery Patterns
 *
 * CONCEPT: In dynamic environments (Kubernetes, cloud), service instances
 * start and stop constantly. Service discovery allows clients to find
 * the current healthy endpoints for a service.
 *
 * Two models:
 *
 * 1. CLIENT-SIDE DISCOVERY (Netflix Eureka, Consul):
 *    Client queries service registry -> Gets list of instances -> Load balances itself
 *    Pro: No single routing LB, client can implement smart routing
 *    Con: Client libraries needed, registry coupling
 *    Example: Spring Cloud with Eureka/Consul
 *
 * 2. SERVER-SIDE DISCOVERY (AWS ELB, Kubernetes Service, Istio):
 *    Client sends to router/LB -> Router discovers instances -> Routes to instance
 *    Pro: Client is simple, language-agnostic
 *    Con: Extra hop, router is infrastructure concern
 *    Example: Kubernetes Service (kube-proxy) + DNS
 *
 * Kubernetes native service discovery:
 * - DNS: my-service.namespace.svc.cluster.local resolves to ClusterIP
 * - Environment variables: MY_SERVICE_SERVICE_HOST, MY_SERVICE_SERVICE_PORT
 * - CoreDNS: K8s' built-in DNS server handles service resolution
 * - Headless Service (clusterIP: None): DNS returns all pod IPs (for Stateful Sets)
 */
public class ServiceDiscoveryDemo {

    record ServiceInstance(String instanceId, String host, int port, Map<String, String> metadata,
                           boolean healthy, long registeredAt) {}

    // ==================== SERVICE REGISTRY ====================

    /**
     * CONCEPT: Service Registry - central catalog of available service instances.
     * Real implementations: Consul, etcd (via Kubernetes), Eureka, Zookeeper.
     */
    static class ServiceRegistry {
        private final Map<String, List<ServiceInstance>> services = new ConcurrentHashMap<>();
        private final ScheduledExecutorService healthCheckScheduler =
                Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "health-check");
                    t.setDaemon(true);
                    return t;
                });

        void register(String serviceName, ServiceInstance instance) {
            services.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>()).add(instance);
            System.out.printf("  [REGISTRY] Registered: %s @ %s:%d%n",
                    serviceName, instance.host(), instance.port());
        }

        void deregister(String serviceName, String instanceId) {
            List<ServiceInstance> instances = services.get(serviceName);
            if (instances != null) {
                instances.removeIf(i -> i.instanceId().equals(instanceId));
                System.out.printf("  [REGISTRY] Deregistered: %s/%s%n", serviceName, instanceId);
            }
        }

        List<ServiceInstance> getHealthyInstances(String serviceName) {
            return services.getOrDefault(serviceName, Collections.emptyList())
                    .stream()
                    .filter(ServiceInstance::healthy)
                    .toList();
        }

        // Start periodic TTL check (deregister instances that stop sending heartbeats)
        void startTtlCheck(long ttlSeconds) {
            healthCheckScheduler.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                services.forEach((name, instances) ->
                        instances.removeIf(i -> {
                            if ((now - i.registeredAt()) > ttlSeconds * 1000) {
                                System.out.printf("  [REGISTRY] TTL expired, deregistering: %s/%s%n",
                                        name, i.instanceId());
                                return true;
                            }
                            return false;
                        })
                );
            }, ttlSeconds, ttlSeconds, TimeUnit.SECONDS);
        }

        void close() { healthCheckScheduler.shutdown(); }

        Map<String, List<ServiceInstance>> getAllServices() {
            return Collections.unmodifiableMap(services);
        }
    }

    // ==================== CLIENT-SIDE DISCOVERY ====================

    /**
     * CONCEPT: Client-side discovery client with built-in load balancing.
     * The client queries the registry and performs its own load balancing.
     * This is what Ribbon (Netflix OSS) and Spring Cloud LoadBalancer do.
     */
    static class ClientSideDiscoveryClient {
        private final ServiceRegistry registry;
        private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

        ClientSideDiscoveryClient(ServiceRegistry registry) {
            this.registry = registry;
        }

        // CONCEPT: Discover and call a service using client-side load balancing
        String call(String serviceName, String request) {
            List<ServiceInstance> instances = registry.getHealthyInstances(serviceName);
            if (instances.isEmpty()) {
                throw new RuntimeException("No healthy instances for service: " + serviceName);
            }

            // Round-robin load balancing (client-side)
            int idx = roundRobinCounters
                    .computeIfAbsent(serviceName, k -> new AtomicInteger(0))
                    .getAndIncrement() % instances.size();
            ServiceInstance instance = instances.get(idx);

            System.out.printf("  [CLIENT] Calling %s @ %s:%d (instance %d/%d)%n",
                    serviceName, instance.host(), instance.port(),
                    idx + 1, instances.size());

            return "Response from " + instance.instanceId() + " for: " + request;
        }
    }

    // ==================== DNS-BASED DISCOVERY (KUBERNETES) ====================

    /**
     * CONCEPT: Kubernetes Service DNS.
     * When you create a K8s Service, CoreDNS automatically creates DNS entries.
     *
     * Service types:
     * - ClusterIP:    Internal DNS only (my-svc.namespace.svc.cluster.local)
     * - NodePort:     External port on each node
     * - LoadBalancer: Cloud LB (AWS ALB, GCP LB)
     * - Headless:     DNS returns pod IPs directly (for StatefulSets, Kafka, etc.)
     */
    static class KubernetesDnsDiscovery {
        private final Map<String, List<String>> dnsRecords = new HashMap<>();

        void simulateService(String serviceName, String namespace, List<String> podIps) {
            // Simulates CoreDNS resolution
            String fqdn = serviceName + "." + namespace + ".svc.cluster.local";
            dnsRecords.put(fqdn, podIps);
        }

        List<String> resolve(String serviceName, String namespace) {
            String fqdn = serviceName + "." + namespace + ".svc.cluster.local";
            List<String> resolved = dnsRecords.getOrDefault(fqdn, Collections.emptyList());
            System.out.printf("  [DNS] %s -> %s%n", fqdn, resolved);
            return resolved;
        }
    }

    // ==================== SIDECAR PROXY DISCOVERY (ISTIO/ENVOY) ====================

    /**
     * CONCEPT: Service Mesh (Istio, Linkerd) uses sidecar proxies for discovery.
     * Every pod gets an Envoy sidecar. Traffic goes:
     *   App -> local Envoy proxy -> target Envoy proxy -> target App
     *
     * Benefits:
     * - Discovery, load balancing, retries, timeouts: all in the proxy
     * - Mutual TLS between all services (mTLS by default)
     * - Distributed tracing headers injected automatically
     * - Circuit breaking at the proxy level
     *
     * The app doesn't know about discovery at all - it just calls "localhost:8080"
     * and Envoy handles routing transparently.
     */
    static class ServiceMeshProxy {
        private final String podId;
        private final Map<String, List<String>> xdsEndpoints = new HashMap<>();

        ServiceMeshProxy(String podId) {
            this.podId = podId;
        }

        // xDS: Discovery protocol between Envoy and Istiod control plane
        void receiveXdsUpdate(String serviceName, List<String> endpoints) {
            xdsEndpoints.put(serviceName, endpoints);
            System.out.printf("  [PROXY/%s] xDS update for %s: %s%n", podId, serviceName, endpoints);
        }

        String intercept(String targetService, String request) {
            List<String> endpoints = xdsEndpoints.getOrDefault(targetService, Collections.emptyList());
            if (endpoints.isEmpty()) return "PROXY_ERROR: No endpoints for " + targetService;

            String endpoint = endpoints.get((int)(Math.random() * endpoints.size()));
            System.out.printf("  [PROXY/%s] Routing to %s (endpoint: %s)%n", podId, targetService, endpoint);
            return "Response from " + endpoint;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Service Discovery Patterns Demo ===");

        demonstrateClientSideDiscovery();
        demonstrateKubernetesDns();
        demonstrateServiceMesh();
        compareDiscoveryMechanisms();
    }

    private static void demonstrateClientSideDiscovery() {
        System.out.println("\n--- Client-Side Discovery (Eureka/Consul style) ---");

        ServiceRegistry registry = new ServiceRegistry();

        // Register service instances
        registry.register("order-service", new ServiceInstance(
                "order-1", "10.0.1.10", 8080, Map.of("zone", "us-east-1a"), true, System.currentTimeMillis()));
        registry.register("order-service", new ServiceInstance(
                "order-2", "10.0.1.11", 8080, Map.of("zone", "us-east-1b"), true, System.currentTimeMillis()));
        registry.register("order-service", new ServiceInstance(
                "order-3", "10.0.1.12", 8080, Map.of("zone", "us-east-1c"), true, System.currentTimeMillis()));

        registry.register("payment-service", new ServiceInstance(
                "payment-1", "10.0.2.10", 9090, Map.of("version", "v2"), true, System.currentTimeMillis()));

        System.out.println("\nRegistered services:");
        registry.getAllServices().forEach((svc, instances) ->
                System.out.printf("  %s: %d instances%n", svc, instances.size()));

        ClientSideDiscoveryClient client = new ClientSideDiscoveryClient(registry);
        System.out.println("\nMaking 6 calls (should round-robin across 3 instances):");
        for (int i = 0; i < 6; i++) {
            client.call("order-service", "getOrder-" + i);
        }

        registry.close();
    }

    private static void demonstrateKubernetesDns() {
        System.out.println("\n--- Kubernetes DNS Discovery ---");

        KubernetesDnsDiscovery dns = new KubernetesDnsDiscovery();

        // Simulate CoreDNS entries created when Services are applied
        dns.simulateService("order-service", "default",
                List.of("10.100.0.10")); // ClusterIP: single virtual IP
        dns.simulateService("order-pods", "default",  // Headless service
                List.of("10.0.1.10", "10.0.1.11", "10.0.1.12")); // Returns all pod IPs

        System.out.println("DNS resolution examples:");
        dns.resolve("order-service", "default");  // ClusterIP -> single VIP
        dns.resolve("order-pods", "default");      // Headless -> multiple pod IPs

        System.out.println("\nK8s internal DNS search path: namespace.svc.cluster.local");
        System.out.println("Short form 'order-service' works within same namespace.");
        System.out.println("Cross-namespace: order-service.other-namespace");
    }

    private static void demonstrateServiceMesh() {
        System.out.println("\n--- Service Mesh (Istio/Envoy sidecar) ---");

        ServiceMeshProxy orderProxy = new ServiceMeshProxy("order-pod-1");
        ServiceMeshProxy paymentProxy = new ServiceMeshProxy("payment-pod-1");

        // Istiod pushes xDS updates (EDS: Endpoint Discovery Service)
        System.out.println("Istiod pushing xDS updates to all sidecars...");
        orderProxy.receiveXdsUpdate("payment-service",
                List.of("payment-pod-1:9090", "payment-pod-2:9090", "payment-pod-3:9090"));
        paymentProxy.receiveXdsUpdate("inventory-service",
                List.of("inventory-pod-1:8080", "inventory-pod-2:8080"));

        System.out.println("\nApp calls payment-service through local sidecar:");
        System.out.println("App code: HttpClient.get('http://payment-service:9090/charge')");
        System.out.println("Envoy intercepts and routes transparently:");
        orderProxy.intercept("payment-service", "charge $99");

        System.out.println("\nService mesh also provides:");
        System.out.println("  - mTLS: automatic mutual TLS between all service pairs");
        System.out.println("  - Observability: distributed tracing, metrics, access logs");
        System.out.println("  - Traffic management: canary, A/B, circuit breaking");
    }

    private static void compareDiscoveryMechanisms() {
        System.out.println("\n--- Discovery Mechanism Comparison ---");
        System.out.printf("%-25s %-15s %-12s %-20s%n", "Mechanism", "Coupling", "Latency", "Use Case");
        System.out.println("-".repeat(75));
        System.out.printf("%-25s %-15s %-12s %-20s%n",
                "K8s Service + DNS", "None", "Low", "Standard K8s workloads");
        System.out.printf("%-25s %-15s %-12s %-20s%n",
                "Client-side (Eureka)", "Library", "Very Low", "Spring Cloud apps");
        System.out.printf("%-25s %-15s %-12s %-20s%n",
                "Service Mesh (Istio)", "Sidecar", "~1ms", "Zero-trust, observability");
        System.out.printf("%-25s %-15s %-12s %-20s%n",
                "Headless Service", "DNS client", "Low", "StatefulSets, Kafka");
    }
}
