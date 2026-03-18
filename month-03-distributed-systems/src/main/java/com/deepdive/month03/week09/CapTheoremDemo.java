package com.deepdive.month03.week09;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Week 9: CAP Theorem
 *
 * CONCEPT: The CAP Theorem (Brewer's Theorem, 2000) states that a distributed
 * data store can only guarantee TWO of the following THREE properties:
 *
 * C - Consistency:   Every read receives the most recent write (all nodes see same data)
 * A - Availability:  Every request receives a response (not necessarily most recent data)
 * P - Partition Tolerance: System continues operating despite network partitions
 *
 * CRITICAL INSIGHT: In a distributed system, network partitions WILL happen.
 * Therefore, "P" is not optional in practice. The real choice is:
 *   CP: Strong consistency, may refuse requests during partition
 *   AP: Always available, may return stale data during partition
 *
 * Real-world system choices:
 * CP systems: HBase, ZooKeeper, etcd, Consul, MongoDB (default config)
 *   -> Banking, inventory management, distributed locks
 * AP systems: Cassandra, DynamoDB (default), CouchDB, Riak
 *   -> Social media, shopping cart, DNS, CDN
 *
 * PACELC extension (2012 - more nuanced):
 * During Partition (P): choose between Availability (A) or Consistency (C)
 * Else (E) when no partition: choose between Latency (L) or Consistency (C)
 *
 * NOTE: "Consistency" in CAP ≠ "Consistency" in ACID!
 *   CAP Consistency = Linearizability (all nodes agree on the current value)
 *   ACID Consistency = Business rule invariants are maintained
 */
public class CapTheoremDemo {

    // Simulate network partition state
    enum NetworkState { CONNECTED, PARTITIONED }

    /**
     * CONCEPT: CP System simulation - prioritizes consistency.
     * During partition: refuse writes (or reads) to maintain consistency.
     * Example: etcd, ZooKeeper (used for service discovery, leader election)
     */
    static class CPDistributedStore {
        private final Map<String, String> primaryStore = new ConcurrentHashMap<>();
        private final Map<String, String> replicaStore = new ConcurrentHashMap<>();
        private NetworkState networkState = NetworkState.CONNECTED;
        private final AtomicLong writeCount = new AtomicLong(0);
        private final AtomicLong refusedCount = new AtomicLong(0);

        // CONCEPT: Write requires majority acknowledgment
        // During partition: refuse writes to prevent divergence
        Optional<String> write(String key, String value) {
            if (networkState == NetworkState.PARTITIONED) {
                refusedCount.incrementAndGet();
                System.out.println("  CP-WRITE REFUSED: Partition detected! Refusing write for key=" + key);
                // WHY: Better to refuse than to accept inconsistent state
                return Optional.empty(); // ServiceUnavailableException in real implementation
            }
            // Normal operation: write to both nodes
            primaryStore.put(key, value);
            replicaStore.put(key, value); // Synchronous replication
            writeCount.incrementAndGet();
            return Optional.of(value);
        }

        // Reads always consistent (no stale reads)
        Optional<String> read(String key) {
            return Optional.ofNullable(primaryStore.get(key));
        }

        void simulatePartition() { networkState = NetworkState.PARTITIONED; }
        void healPartition() {
            networkState = NetworkState.CONNECTED;
            replicaStore.putAll(primaryStore); // Sync on heal
        }

        void printStats() {
            System.out.printf("  CP Store: writes=%d, refused=%d, primary=%d, replica=%d%n",
                    writeCount.get(), refusedCount.get(), primaryStore.size(), replicaStore.size());
        }
    }

    /**
     * CONCEPT: AP System simulation - prioritizes availability.
     * During partition: accept writes on both sides (divergence allowed).
     * After partition heals: resolve conflicts (last-write-wins, CRDT, etc.)
     * Example: Cassandra, DynamoDB
     */
    static class APDistributedStore {
        // Each node has its own store (diverges during partition)
        private final Map<String, VersionedValue> nodeA = new ConcurrentHashMap<>();
        private final Map<String, VersionedValue> nodeB = new ConcurrentHashMap<>();
        private NetworkState networkState = NetworkState.CONNECTED;

        record VersionedValue(String value, long timestamp, String nodeId) {}

        // Write always succeeds (AP - availability guaranteed)
        void writeToNodeA(String key, String value) {
            VersionedValue vv = new VersionedValue(value, System.nanoTime(), "node-A");
            nodeA.put(key, vv);
            if (networkState == NetworkState.CONNECTED) {
                // Synchronous replication when connected
                nodeB.put(key, vv);
            }
            // During partition: only nodeA gets the write (divergence!)
        }

        void writeToNodeB(String key, String value) {
            VersionedValue vv = new VersionedValue(value, System.nanoTime(), "node-B");
            nodeB.put(key, vv);
            if (networkState == NetworkState.CONNECTED) {
                nodeA.put(key, vv);
            }
        }

        // Read may return stale data (eventual consistency)
        Optional<VersionedValue> readFromNodeA(String key) {
            return Optional.ofNullable(nodeA.get(key));
        }

        Optional<VersionedValue> readFromNodeB(String key) {
            return Optional.ofNullable(nodeB.get(key));
        }

        void simulatePartition() { networkState = NetworkState.PARTITIONED; }

        // CONCEPT: Conflict resolution on partition heal
        // Last-Write-Wins (LWW) is simplest but can lose data
        // CRDTs (Conflict-free Replicated Data Types) enable automatic merging
        void healPartition_LWW() {
            networkState = NetworkState.CONNECTED;
            System.out.println("  AP-HEAL: Resolving conflicts using Last-Write-Wins...");
            Set<String> allKeys = new HashSet<>(nodeA.keySet());
            allKeys.addAll(nodeB.keySet());

            for (String key : allKeys) {
                VersionedValue va = nodeA.get(key);
                VersionedValue vb = nodeB.get(key);
                VersionedValue winner;

                if (va == null) winner = vb;
                else if (vb == null) winner = va;
                else {
                    winner = va.timestamp() >= vb.timestamp() ? va : vb;
                    if (!va.value().equals(vb.value())) {
                        System.out.println("  AP-CONFLICT: key='" + key + "' nodeA='" + va.value() +
                                "' nodeB='" + vb.value() + "' -> winner: " + winner.nodeId());
                    }
                }
                nodeA.put(key, winner);
                nodeB.put(key, winner);
            }
        }

        boolean isConsistent() {
            return nodeA.equals(nodeB);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== CAP Theorem Demo ===");

        demonstrateCPSystem();
        demonstrateAPSystem();
        demonstratePACELC();
        discussRealWorldChoices();
    }

    private static void demonstrateCPSystem() throws InterruptedException {
        System.out.println("\n--- CP System (Consistency + Partition Tolerance) ---");
        CPDistributedStore store = new CPDistributedStore();

        // Normal operation
        store.write("balance:user-1", "1000");
        store.write("balance:user-2", "500");
        System.out.println("Normal write: balance:user-1 = " + store.read("balance:user-1").orElse("null"));
        store.printStats();

        // Simulate network partition
        System.out.println("\n  ** NETWORK PARTITION **");
        store.simulatePartition();

        // CP: writes rejected during partition
        Optional<String> result = store.write("balance:user-1", "900"); // Transfer happened
        System.out.println("Write during partition: " + result.map(v -> "OK").orElse("REJECTED"));
        store.printStats();

        // CP: reads still work (from primary)
        System.out.println("Read during partition: " + store.read("balance:user-1").orElse("null"));

        // Heal partition
        System.out.println("\n  ** PARTITION HEALED **");
        store.healPartition();
        store.write("balance:user-1", "900"); // Now succeeds
        System.out.println("Post-heal write: " + store.read("balance:user-1").orElse("null"));
        store.printStats();

        System.out.println("\nCP systems: Banks, inventory, distributed locks (ZooKeeper, etcd)");
    }

    private static void demonstrateAPSystem() throws InterruptedException {
        System.out.println("\n--- AP System (Availability + Partition Tolerance) ---");
        APDistributedStore store = new APDistributedStore();

        // Normal operation
        store.writeToNodeA("cart:user-1", "['LAPTOP']");
        System.out.println("Normal: nodeA=" + store.readFromNodeA("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("Normal: nodeB=" + store.readFromNodeB("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("Consistent: " + store.isConsistent());

        // Simulate partition
        System.out.println("\n  ** NETWORK PARTITION **");
        store.simulatePartition();
        Thread.sleep(10);

        // AP: BOTH nodes accept writes (divergence occurs!)
        store.writeToNodeA("cart:user-1", "['LAPTOP', 'MOUSE']");    // User adds MOUSE on node A
        Thread.sleep(5);
        store.writeToNodeB("cart:user-1", "['LAPTOP', 'KEYBOARD']"); // User adds KEYBOARD on node B

        System.out.println("During partition:");
        System.out.println("  nodeA: " + store.readFromNodeA("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("  nodeB: " + store.readFromNodeB("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("  Consistent: " + store.isConsistent());

        // Heal partition
        System.out.println("\n  ** PARTITION HEALED - Conflict Resolution **");
        store.healPartition_LWW();
        System.out.println("After LWW resolution:");
        System.out.println("  nodeA: " + store.readFromNodeA("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("  nodeB: " + store.readFromNodeB("cart:user-1").map(v -> v.value()).orElse("null"));
        System.out.println("  Consistent: " + store.isConsistent());
        System.out.println("  WARNING: One user's cart update was LOST (LWW data loss)");
        System.out.println("  BETTER: Use CRDT (add-only set) to merge: ['LAPTOP', 'MOUSE', 'KEYBOARD']");
    }

    private static void demonstratePACELC() {
        System.out.println("\n--- PACELC Model (More Realistic Than CAP) ---");
        System.out.println("CAP focuses only on partition behavior.");
        System.out.println("PACELC acknowledges that latency vs consistency trade-off ALWAYS exists.");
        System.out.println();
        System.out.println("PACELC choices:");
        System.out.printf("  %-20s %-12s %-12s%n", "System", "P: A or C", "E: L or C");
        System.out.println("-".repeat(50));
        System.out.printf("  %-20s %-12s %-12s%n", "DynamoDB (default)", "PA", "EL");
        System.out.printf("  %-20s %-12s %-12s%n", "DynamoDB (strong)", "PC", "EC");
        System.out.printf("  %-20s %-12s %-12s%n", "Cassandra (ONE)", "PA", "EL");
        System.out.printf("  %-20s %-12s %-12s%n", "Cassandra (QUORUM)", "PC", "EC");
        System.out.printf("  %-20s %-12s %-12s%n", "ZooKeeper/etcd", "PC", "EC");
        System.out.printf("  %-20s %-12s %-12s%n", "MySQL (async repl)", "PA", "EL");
        System.out.printf("  %-20s %-12s %-12s%n", "MySQL (sync repl)", "PC", "EC");
    }

    private static void discussRealWorldChoices() {
        System.out.println("\n--- Real-World CAP Choices ---");
        System.out.println("Choose CP when:");
        System.out.println("  - Financial transactions (money transfers, inventory decrement)");
        System.out.println("  - Distributed locks and leader election");
        System.out.println("  - Any operation that CANNOT be repeated (non-idempotent)");
        System.out.println();
        System.out.println("Choose AP when:");
        System.out.println("  - User profiles, social media feeds, recommendations");
        System.out.println("  - Shopping cart (can merge with CRDTs)");
        System.out.println("  - DNS lookups, CDN content, product catalog");
        System.out.println("  - Any operation that CAN tolerate stale data");
        System.out.println();
        System.out.println("Staff Engineer insight: Most systems need BOTH CP and AP.");
        System.out.println("  Design data flows carefully: what data needs strong consistency?");
        System.out.println("  Example: Amazon cart is AP (CRDTs), but payment is CP (2PC/SAGA)");
    }
}
