package com.deepdive.month04.week13;

import java.util.*;

/**
 * Week 13: Kubernetes Client (fabric8 kubernetes-client patterns)
 *
 * CONCEPT: The Kubernetes API server exposes a RESTful API that all kubectl
 * commands use. Java applications can interact with K8s programmatically
 * using the fabric8 kubernetes-client library.
 *
 * Key use cases for Java K8s client:
 * - Kubernetes Operators: Extend K8s with custom controllers
 * - Admission Webhooks: Validate/mutate resources before creation
 * - Service Mesh Sidecars: Dynamic configuration
 * - CI/CD tooling: Create/delete deployments, jobs
 * - Auto-scaling logic: Read metrics, adjust replicas
 *
 * K8s API structure:
 * - API Groups: core (/api/v1), apps (/apis/apps/v1), etc.
 * - Resources: pods, services, deployments, configmaps, secrets
 * - Namespaces: logical isolation boundary
 * - Watch: event stream for resource changes (basis of all controllers)
 *
 * Controller pattern (reconciliation loop):
 *   while (true) {
 *     desired = getDesiredState()    // from Spec
 *     actual  = getCurrentState()   // from Status
 *     if (desired != actual) reconcile(desired, actual)
 *     sleep(reconcileInterval)
 *   }
 *
 * NOTE: To run this against a real cluster:
 * 1. Uncomment fabric8 dependency in month-04/build.gradle
 * 2. Ensure ~/.kube/config exists with valid credentials
 * 3. Run: minikube start (for local dev)
 */
public class KubernetesClientDemo {

    // ==================== SIMULATED K8s RESOURCE MODELS ====================
    // (In real code, these are generated from fabric8 or official K8s client)

    record ObjectMeta(String name, String namespace, Map<String, String> labels,
                      Map<String, String> annotations, String resourceVersion) {}

    record Container(String name, String image, List<Integer> ports,
                     Map<String, String> env, ResourceRequirements resources) {}

    record ResourceRequirements(Map<String, String> requests, Map<String, String> limits) {}

    record PodSpec(List<Container> containers, String restartPolicy, String serviceAccountName) {}
    record PodStatus(String phase, String podIP, boolean ready) {}
    record Pod(ObjectMeta metadata, PodSpec spec, PodStatus status) {}

    record DeploymentSpec(int replicas, String selector, PodSpec template) {}
    record DeploymentStatus(int readyReplicas, int availableReplicas, int updatedReplicas) {}
    record Deployment(ObjectMeta metadata, DeploymentSpec spec, DeploymentStatus status) {}

    record ServicePort(String name, int port, int targetPort, String protocol) {}
    record ServiceSpec(String type, List<ServicePort> ports, Map<String, String> selector) {}
    record Service(ObjectMeta metadata, ServiceSpec spec) {}

    record ConfigMap(ObjectMeta metadata, Map<String, String> data) {}
    record SecretData(ObjectMeta metadata, Map<String, String> data, String type) {}

    // ==================== SIMULATED K8s CLIENT ====================

    /**
     * CONCEPT: Simulates the fabric8 KubernetesClient API.
     * Real usage:
     * KubernetesClient client = new KubernetesClientBuilder().build();
     * client.pods().inNamespace("default").list()
     */
    static class SimulatedKubernetesClient {
        private final Map<String, List<Pod>> pods = new HashMap<>();
        private final Map<String, List<Deployment>> deployments = new HashMap<>();
        private final Map<String, List<Service>> services = new HashMap<>();
        private final Map<String, List<ConfigMap>> configMaps = new HashMap<>();

        // CONCEPT: Client fluent API
        PodOperations pods() { return new PodOperations(this); }
        DeploymentOperations deployments() { return new DeploymentOperations(this); }
        ServiceOperations services() { return new ServiceOperations(this); }

        static class PodOperations {
            private final SimulatedKubernetesClient client;
            private String namespace = "default";

            PodOperations(SimulatedKubernetesClient client) { this.client = client; }

            PodOperations inNamespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            List<Pod> list() {
                return client.pods.getOrDefault(namespace, Collections.emptyList());
            }

            Optional<Pod> withName(String name) {
                return list().stream().filter(p -> p.metadata().name().equals(name)).findFirst();
            }

            Pod create(Pod pod) {
                client.pods.computeIfAbsent(namespace, k -> new ArrayList<>()).add(pod);
                System.out.printf("  Created Pod %s/%s%n", namespace, pod.metadata().name());
                return pod;
            }

            boolean delete(String name) {
                List<Pod> ns = client.pods.getOrDefault(namespace, new ArrayList<>());
                boolean removed = ns.removeIf(p -> p.metadata().name().equals(name));
                if (removed) System.out.printf("  Deleted Pod %s/%s%n", namespace, name);
                return removed;
            }
        }

        static class DeploymentOperations {
            private final SimulatedKubernetesClient client;
            private String namespace = "default";

            DeploymentOperations(SimulatedKubernetesClient client) { this.client = client; }

            DeploymentOperations inNamespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            List<Deployment> list() {
                return client.deployments.getOrDefault(namespace, Collections.emptyList());
            }

            Deployment create(Deployment deployment) {
                client.deployments.computeIfAbsent(namespace, k -> new ArrayList<>()).add(deployment);
                System.out.printf("  Created Deployment %s/%s (replicas=%d)%n",
                        namespace, deployment.metadata().name(), deployment.spec().replicas());
                return deployment;
            }

            // CONCEPT: Scale a deployment by updating replicas
            void scale(String name, int replicas) {
                client.deployments.getOrDefault(namespace, new ArrayList<>()).stream()
                        .filter(d -> d.metadata().name().equals(name))
                        .findFirst()
                        .ifPresent(d -> {
                            System.out.printf("  Scaled Deployment %s/%s to %d replicas%n",
                                    namespace, name, replicas);
                        });
            }
        }

        static class ServiceOperations {
            private final SimulatedKubernetesClient client;
            private String namespace = "default";

            ServiceOperations(SimulatedKubernetesClient client) { this.client = client; }

            ServiceOperations inNamespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            Service create(Service service) {
                client.services.computeIfAbsent(namespace, k -> new ArrayList<>()).add(service);
                System.out.printf("  Created Service %s/%s (type=%s)%n",
                        namespace, service.metadata().name(), service.spec().type());
                return service;
            }

            List<Service> list() {
                return client.services.getOrDefault(namespace, Collections.emptyList());
            }
        }
    }

    // ==================== KUBERNETES OPERATOR PATTERN ====================

    /**
     * CONCEPT: A Kubernetes Operator is an application that:
     * 1. Defines a Custom Resource Definition (CRD)
     * 2. Watches for changes to those custom resources
     * 3. Reconciles the desired state (CRD spec) with actual state
     *
     * Example: An "Application" CRD that automatically creates Deployment + Service + HPA
     */
    record AppSpec(String image, int replicas, int port, Map<String, String> envVars) {}
    record AppStatus(String phase, int readyReplicas, String message) {}
    record ApplicationCR(ObjectMeta metadata, AppSpec spec, AppStatus status) {}

    static class ApplicationOperator {
        private final SimulatedKubernetesClient k8sClient;

        ApplicationOperator(SimulatedKubernetesClient client) {
            this.k8sClient = client;
        }

        /**
         * CONCEPT: Reconcile loop - compare desired state (spec) with actual state.
         * This runs every time the CRD is created/updated, or on a timer.
         */
        void reconcile(ApplicationCR app) {
            System.out.printf("  Reconciling Application '%s'...%n", app.metadata().name());
            String appName = app.metadata().name();
            String namespace = app.metadata().namespace();

            // Check if Deployment exists
            List<Deployment> existing = k8sClient.deployments().inNamespace(namespace).list();
            boolean deploymentExists = existing.stream()
                    .anyMatch(d -> d.metadata().name().equals(appName));

            if (!deploymentExists) {
                // Create Deployment
                Deployment deployment = createDeployment(app);
                k8sClient.deployments().inNamespace(namespace).create(deployment);

                // Create Service
                Service service = createService(app);
                k8sClient.services().inNamespace(namespace).create(service);

                System.out.println("  Operator: Created Deployment and Service for " + appName);
            } else {
                // Check if replicas need updating
                System.out.println("  Operator: Resources already exist for " + appName + ", checking drift...");
                // In real operator: patch if spec changed
            }
        }

        private Deployment createDeployment(ApplicationCR app) {
            Container container = new Container(
                    app.metadata().name(),
                    app.spec().image(),
                    List.of(app.spec().port()),
                    app.spec().envVars(),
                    new ResourceRequirements(
                            Map.of("cpu", "100m", "memory", "128Mi"),
                            Map.of("cpu", "500m", "memory", "512Mi")
                    )
            );
            return new Deployment(
                    new ObjectMeta(app.metadata().name(), app.metadata().namespace(),
                            Map.of("app", app.metadata().name()), Map.of(), "1"),
                    new DeploymentSpec(app.spec().replicas(),
                            "app=" + app.metadata().name(),
                            new PodSpec(List.of(container), "Always", "default")),
                    new DeploymentStatus(0, 0, 0)
            );
        }

        private Service createService(ApplicationCR app) {
            return new Service(
                    new ObjectMeta(app.metadata().name(), app.metadata().namespace(),
                            Map.of("app", app.metadata().name()), Map.of(), "1"),
                    new ServiceSpec("ClusterIP",
                            List.of(new ServicePort("http", 80, app.spec().port(), "TCP")),
                            Map.of("app", app.metadata().name()))
            );
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Kubernetes Client Demo ===");
        System.out.println("(Simulated client - connect real fabric8 client to actual cluster)");

        SimulatedKubernetesClient client = new SimulatedKubernetesClient();

        demonstratePodManagement(client);
        demonstrateDeploymentManagement(client);
        demonstrateOperatorPattern(client);
        explainKubernetesInternals();
    }

    private static void demonstratePodManagement(SimulatedKubernetesClient client) {
        System.out.println("\n--- Pod Management ---");

        Pod pod = new Pod(
                new ObjectMeta("my-app-abc12", "production",
                        Map.of("app", "my-app", "version", "v1.2.0"), Map.of(), "1"),
                new PodSpec(
                        List.of(new Container("app", "my-app:1.2.0", List.of(8080),
                                Map.of("SPRING_PROFILES_ACTIVE", "prod"),
                                new ResourceRequirements(
                                        Map.of("cpu", "250m", "memory", "256Mi"),
                                        Map.of("cpu", "1000m", "memory", "512Mi")))),
                        "Always", "app-service-account"),
                new PodStatus("Running", "10.0.1.42", true)
        );

        client.pods().inNamespace("production").create(pod);

        List<Pod> pods = client.pods().inNamespace("production").list();
        System.out.println("Pods in production namespace: " + pods.size());
        pods.forEach(p -> System.out.printf("  Pod: %s | IP: %s | Ready: %s%n",
                p.metadata().name(), p.status().podIP(), p.status().ready()));
    }

    private static void demonstrateDeploymentManagement(SimulatedKubernetesClient client) {
        System.out.println("\n--- Deployment Management ---");

        Deployment deployment = new Deployment(
                new ObjectMeta("order-service", "production",
                        Map.of("app", "order-service"), Map.of(), "1"),
                new DeploymentSpec(3, "app=order-service",
                        new PodSpec(
                                List.of(new Container("order-service", "order-service:2.0.0",
                                        List.of(8080), Map.of("DB_URL", "jdbc:postgres://db:5432/orders"),
                                        new ResourceRequirements(
                                                Map.of("cpu", "200m", "memory", "256Mi"),
                                                Map.of("cpu", "1", "memory", "1Gi")))),
                                "Always", "order-service-sa")),
                new DeploymentStatus(3, 3, 3)
        );

        client.deployments().inNamespace("production").create(deployment);
        System.out.println("Deployments: " + client.deployments().inNamespace("production").list().size());

        // Scale
        client.deployments().inNamespace("production").scale("order-service", 5);
    }

    private static void demonstrateOperatorPattern(SimulatedKubernetesClient client) {
        System.out.println("\n--- Kubernetes Operator Pattern ---");

        ApplicationOperator operator = new ApplicationOperator(client);

        // Custom resource instance
        ApplicationCR app = new ApplicationCR(
                new ObjectMeta("payment-service", "staging",
                        Map.of("app", "payment-service"), Map.of(), "1"),
                new AppSpec("payment-service:1.5.0", 2, 8080,
                        Map.of("PAYMENT_GATEWAY", "stripe", "LOG_LEVEL", "INFO")),
                new AppStatus("Pending", 0, "Initializing")
        );

        // Operator reconciles - creates all necessary K8s resources
        operator.reconcile(app);
        // Second reconcile: idempotent, no duplicate resources
        operator.reconcile(app);
    }

    private static void explainKubernetesInternals() {
        System.out.println("\n--- Kubernetes Architecture ---");
        System.out.println("Control Plane:");
        System.out.println("  API Server:   Central hub. All kubectl/client requests go here.");
        System.out.println("  etcd:         Distributed KV store for cluster state (uses Raft!)");
        System.out.println("  Scheduler:    Assigns Pods to Nodes based on resources/affinity");
        System.out.println("  Controller Manager: Runs reconciliation loops for built-in resources");
        System.out.println();
        System.out.println("Worker Nodes:");
        System.out.println("  kubelet:      Node agent. Ensures containers are running as specified.");
        System.out.println("  kube-proxy:   Network rules for Service routing (iptables/eBPF)");
        System.out.println("  Container Runtime: containerd, CRI-O (replaced Docker in K8s 1.24)");
        System.out.println();
        System.out.println("fabric8 real usage:");
        System.out.println("  KubernetesClient client = new KubernetesClientBuilder().build();");
        System.out.println("  // Watch for Pod events:");
        System.out.println("  client.pods().inNamespace(\"default\").watch(new Watcher<Pod>() {");
        System.out.println("    public void eventReceived(Action action, Pod pod) { ... }");
        System.out.println("  });");
    }
}
