package com.deepdive.month04.week16;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Week 16: Kubernetes RBAC (Role-Based Access Control)
 *
 * CONCEPT: RBAC controls who can do what in a Kubernetes cluster.
 * It answers: "Can subject X perform action Y on resource Z?"
 *
 * Core objects:
 * - Role / ClusterRole:   Defines WHAT can be done (permissions)
 *   - Role: namespaced (applies to one namespace)
 *   - ClusterRole: cluster-wide (applies everywhere)
 *
 * - RoleBinding / ClusterRoleBinding:  Assigns roles to subjects
 *   - RoleBinding: binds role to subject in one namespace
 *   - ClusterRoleBinding: binds ClusterRole cluster-wide
 *
 * Subjects:
 * - ServiceAccount: Identity for pods/applications
 * - User:           Human users (authenticated via certificates, OIDC, etc.)
 * - Group:          Group of users
 *
 * Verbs (operations): get, list, watch, create, update, patch, delete
 * Resources: pods, services, deployments, configmaps, secrets, etc.
 *
 * RBAC is ADDITIVE only - start with least privilege, add as needed.
 * There is NO explicit DENY - absence of rule = no permission.
 *
 * Best practices:
 * - Principle of least privilege: Grant only what's needed
 * - Per-service ServiceAccounts: Don't share the default SA
 * - Avoid ClusterRoleBindings for application code
 * - Audit regularly: kubectl auth can-i --list --as=system:serviceaccount:ns:sa
 */
public class RbacConceptsDemo {

    // ==================== RBAC MODEL ====================

    record PolicyRule(List<String> apiGroups, List<String> resources, List<String> verbs) {
        boolean allows(String resource, String verb) {
            boolean resourceMatch = resources.contains("*") || resources.contains(resource);
            boolean verbMatch = verbs.contains("*") || verbs.contains(verb);
            return resourceMatch && verbMatch;
        }
    }

    record Role(String name, String namespace, List<PolicyRule> rules) {}
    record ClusterRole(String name, List<PolicyRule> rules) {}

    sealed interface Subject permits User, ServiceAccountSubject, GroupSubject {}
    record User(String name) implements Subject {}
    record ServiceAccountSubject(String name, String namespace) implements Subject {}
    record GroupSubject(String name) implements Subject {}

    record RoleBinding(String name, String namespace, String roleRef, List<Subject> subjects) {}
    record ClusterRoleBinding(String name, String roleRef, List<Subject> subjects) {}

    // ==================== RBAC ENGINE ====================

    static class RbacEngine {
        private final Map<String, Role> roles = new HashMap<>();
        private final Map<String, ClusterRole> clusterRoles = new HashMap<>();
        private final List<RoleBinding> roleBindings = new ArrayList<>();
        private final List<ClusterRoleBinding> clusterRoleBindings = new ArrayList<>();

        void addRole(Role role) {
            roles.put(role.namespace() + "/" + role.name(), role);
        }

        void addClusterRole(ClusterRole clusterRole) {
            clusterRoles.put(clusterRole.name(), clusterRole);
        }

        void addRoleBinding(RoleBinding binding) { roleBindings.add(binding); }
        void addClusterRoleBinding(ClusterRoleBinding binding) { clusterRoleBindings.add(binding); }

        /**
         * CONCEPT: Authorization check - "Can subject X do verb Y on resource Z in namespace N?"
         * This mirrors kubectl auth can-i <verb> <resource> --as=<subject> -n <namespace>
         */
        boolean canI(Subject subject, String verb, String resource, String namespace) {
            // Check namespace-scoped RoleBindings
            for (RoleBinding binding : roleBindings) {
                if (!binding.namespace().equals(namespace)) continue;
                if (!binds(binding.subjects(), subject)) continue;

                // Check if bound role allows this action
                Role role = roles.get(namespace + "/" + binding.roleRef());
                if (role != null && roleAllows(role.rules(), resource, verb)) return true;

                // ClusterRole can be bound in a namespace via RoleBinding
                ClusterRole cr = clusterRoles.get(binding.roleRef());
                if (cr != null && roleAllows(cr.rules(), resource, verb)) return true;
            }

            // Check ClusterRoleBindings (cluster-wide)
            for (ClusterRoleBinding binding : clusterRoleBindings) {
                if (!binds(binding.subjects(), subject)) continue;
                ClusterRole cr = clusterRoles.get(binding.roleRef());
                if (cr != null && roleAllows(cr.rules(), resource, verb)) return true;
            }

            return false;
        }

        private boolean binds(List<Subject> subjects, Subject target) {
            return subjects.stream().anyMatch(s -> s.equals(target));
        }

        private boolean roleAllows(List<PolicyRule> rules, String resource, String verb) {
            return rules.stream().anyMatch(rule -> rule.allows(resource, verb));
        }

        // Show all permissions for a subject
        void describePermissions(Subject subject, String namespace) {
            System.out.printf("\n  Permissions for %s in namespace '%s':%n", subject, namespace);
            String[] resources = {"pods", "services", "deployments", "configmaps", "secrets"};
            String[] verbs = {"get", "list", "create", "update", "delete"};

            for (String resource : resources) {
                List<String> allowed = Arrays.stream(verbs)
                        .filter(v -> canI(subject, v, resource, namespace))
                        .collect(Collectors.toList());
                if (!allowed.isEmpty()) {
                    System.out.printf("    %-15s %s%n", resource, allowed);
                }
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Kubernetes RBAC Demo ===");

        RbacEngine rbac = setupRbacPolicies();

        demonstrateAccessControl(rbac);
        demonstrateLeastPrivilege(rbac);
        demonstrateServiceAccountBestPractices();
        showRbacYamlExamples();
    }

    private static RbacEngine setupRbacPolicies() {
        RbacEngine rbac = new RbacEngine();

        // CONCEPT: Read-only role for monitoring/debugging
        rbac.addRole(new Role("pod-reader", "production", List.of(
                new PolicyRule(List.of(""), List.of("pods", "pods/log", "pods/exec"),
                        List.of("get", "list", "watch"))
        )));

        // CONCEPT: Application role - can read configmaps but not secrets
        rbac.addRole(new Role("app-reader", "production", List.of(
                new PolicyRule(List.of(""), List.of("configmaps"),
                        List.of("get", "list", "watch")),
                new PolicyRule(List.of(""), List.of("secrets"),
                        List.of()) // EXPLICITLY NO SECRET ACCESS
        )));

        // CONCEPT: Deployment manager role
        rbac.addRole(new Role("deployment-manager", "production", List.of(
                new PolicyRule(List.of("apps"), List.of("deployments", "replicasets"),
                        List.of("get", "list", "watch", "create", "update", "patch")),
                new PolicyRule(List.of(""), List.of("pods"), List.of("get", "list", "watch"))
        )));

        // CONCEPT: ClusterRole for cross-namespace access (node reader for monitoring)
        rbac.addClusterRole(new ClusterRole("node-reader", List.of(
                new PolicyRule(List.of(""), List.of("nodes", "persistentvolumes"),
                        List.of("get", "list", "watch"))
        )));

        // CONCEPT: Admin ClusterRole (dangerous - avoid in production code)
        rbac.addClusterRole(new ClusterRole("cluster-admin-lite", List.of(
                new PolicyRule(List.of("*"), List.of("*"), List.of("*"))
        )));

        // Bind roles to subjects
        // Developer gets pod-reader in production (read-only debugging)
        rbac.addRoleBinding(new RoleBinding("developer-pod-reader", "production",
                "pod-reader", List.of(new GroupSubject("developers"))));

        // Order service account gets configmap access
        rbac.addRoleBinding(new RoleBinding("order-service-config", "production",
                "app-reader", List.of(new ServiceAccountSubject("order-service", "production"))));

        // CI/CD system account gets deployment manager
        rbac.addRoleBinding(new RoleBinding("cicd-deploy", "production",
                "deployment-manager", List.of(new ServiceAccountSubject("cicd-bot", "ci-system"))));

        // Monitoring gets cluster-wide node read access
        rbac.addClusterRoleBinding(new ClusterRoleBinding("monitoring-node-reader",
                "node-reader", List.of(new ServiceAccountSubject("prometheus", "monitoring"))));

        return rbac;
    }

    private static void demonstrateAccessControl(RbacEngine rbac) {
        System.out.println("\n--- Access Control Checks ---");

        User developer = new User("alice");
        ServiceAccountSubject orderSvc = new ServiceAccountSubject("order-service", "production");
        ServiceAccountSubject cicd = new ServiceAccountSubject("cicd-bot", "ci-system");

        System.out.println("Developer alice:");
        System.out.printf("  pods/get in production:         %s%n",
                rbac.canI(new GroupSubject("developers"), "get", "pods", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  pods/delete in production:      %s%n",
                rbac.canI(new GroupSubject("developers"), "delete", "pods", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  deployments/update production:  %s%n",
                rbac.canI(new GroupSubject("developers"), "update", "deployments", "production") ? "ALLOWED" : "DENIED");

        System.out.println("\nOrder service account:");
        System.out.printf("  configmaps/get in production:   %s%n",
                rbac.canI(orderSvc, "get", "configmaps", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  secrets/get in production:      %s%n",
                rbac.canI(orderSvc, "get", "secrets", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  deployments/create production:  %s%n",
                rbac.canI(orderSvc, "create", "deployments", "production") ? "ALLOWED" : "DENIED");

        System.out.println("\nCI/CD bot:");
        System.out.printf("  deployments/update in prod:     %s%n",
                rbac.canI(cicd, "update", "deployments", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  deployments/create in prod:     %s%n",
                rbac.canI(cicd, "create", "deployments", "production") ? "ALLOWED" : "DENIED");
        System.out.printf("  pods/delete in production:      %s%n",
                rbac.canI(cicd, "delete", "pods", "production") ? "ALLOWED" : "DENIED");
    }

    private static void demonstrateLeastPrivilege(RbacEngine rbac) {
        System.out.println("\n--- Principle of Least Privilege ---");
        rbac.describePermissions(new GroupSubject("developers"), "production");
        rbac.describePermissions(new ServiceAccountSubject("order-service", "production"), "production");
        rbac.describePermissions(new ServiceAccountSubject("cicd-bot", "ci-system"), "production");
    }

    private static void demonstrateServiceAccountBestPractices() {
        System.out.println("\n--- ServiceAccount Best Practices ---");
        System.out.println("DON'T use the default service account (has no permissions but is messy).");
        System.out.println("DON'T share service accounts between different applications.");
        System.out.println("DO create dedicated ServiceAccount per application:");
        System.out.println();
        System.out.println("  # ServiceAccount YAML:");
        System.out.println("  apiVersion: v1");
        System.out.println("  kind: ServiceAccount");
        System.out.println("  metadata:");
        System.out.println("    name: order-service");
        System.out.println("    namespace: production");
        System.out.println("    annotations:");
        System.out.println("      # AWS: EKS Pod Identity / IRSA (IAM role for K8s SA)");
        System.out.println("      eks.amazonaws.com/role-arn: arn:aws:iam::123456789012:role/order-service");
        System.out.println();
        System.out.println("  # Disable automountServiceAccountToken if not needed:");
        System.out.println("  automountServiceAccountToken: false");
    }

    private static void showRbacYamlExamples() {
        System.out.println("\n--- RBAC YAML Examples ---");
        System.out.println("# Role: Allow reading configmaps");
        System.out.println("apiVersion: rbac.authorization.k8s.io/v1");
        System.out.println("kind: Role");
        System.out.println("metadata:");
        System.out.println("  name: configmap-reader");
        System.out.println("  namespace: production");
        System.out.println("rules:");
        System.out.println("- apiGroups: [\"\"]");
        System.out.println("  resources: [\"configmaps\"]");
        System.out.println("  verbs: [\"get\", \"list\", \"watch\"]");
        System.out.println();
        System.out.println("# RoleBinding: Bind to service account");
        System.out.println("apiVersion: rbac.authorization.k8s.io/v1");
        System.out.println("kind: RoleBinding");
        System.out.println("metadata:");
        System.out.println("  name: order-service-configmap-reader");
        System.out.println("  namespace: production");
        System.out.println("subjects:");
        System.out.println("- kind: ServiceAccount");
        System.out.println("  name: order-service");
        System.out.println("  namespace: production");
        System.out.println("roleRef:");
        System.out.println("  kind: Role");
        System.out.println("  name: configmap-reader");
        System.out.println("  apiGroup: rbac.authorization.k8s.io");
        System.out.println();
        System.out.println("# Audit: check effective permissions");
        System.out.println("kubectl auth can-i get pods --as=system:serviceaccount:production:order-service -n production");
        System.out.println("kubectl auth can-i --list --as=system:serviceaccount:production:order-service -n production");
    }
}
