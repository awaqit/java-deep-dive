package com.deepdive.month03.week09;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Week 9: Eventual Consistency and Vector Clocks
 *
 * CONCEPT: Eventual Consistency guarantees that if no new updates are made to a
 * distributed object, eventually all replicas will converge to the same value.
 *
 * This is weaker than strong consistency (linearizability) but enables:
 * - Higher availability
 * - Lower latency (no need to coordinate across all nodes before responding)
 * - Better partition tolerance
 *
 * Vector Clocks:
 * A vector clock is a data structure that tracks causality in distributed systems.
 * Each node maintains a vector [n1, n2, n3, ...] where ni = events seen from node i.
 *
 * Rules:
 * 1. When node i does a local event: increment vi
 * 2. When node i sends a message: attach current vector clock
 * 3. When node i receives message with clock C: vi = max(vi, Ci) + 1
 *
 * Vector clocks help answer: "Did event A happen before event B?"
 * - A → B (A happened-before B): A.clock[all nodes] <= B.clock[all nodes]
 * - A || B (concurrent): neither happened-before the other -> potential conflict
 *
 * Real-world usage: Amazon Dynamo used vector clocks (now uses a simplified variant),
 * Cassandra uses hybrid logical clocks, Riak uses vector clocks.
 */
public class EventualConsistencyDemo {

    // ==================== VECTOR CLOCK ====================

    static class VectorClock {
        private final Map<String, Long> clock = new ConcurrentHashMap<>();

        VectorClock() {}

        VectorClock(Map<String, Long> initialClock) {
            clock.putAll(initialClock);
        }

        // Increment clock for this node
        VectorClock increment(String nodeId) {
            VectorClock newClock = copy();
            newClock.clock.merge(nodeId, 1L, Long::sum);
            return newClock;
        }

        // Merge with another clock (take max of each element)
        VectorClock merge(VectorClock other) {
            VectorClock merged = copy();
            other.clock.forEach((nodeId, time) ->
                    merged.clock.merge(nodeId, time, Long::max));
            return merged;
        }

        // A happened-before B if A.clock[i] <= B.clock[i] for all i, and < for at least one
        boolean happenedBefore(VectorClock other) {
            Set<String> allNodes = new HashSet<>(clock.keySet());
            allNodes.addAll(other.clock.keySet());
            boolean strictlyLess = false;
            for (String node : allNodes) {
                long myTime = clock.getOrDefault(node, 0L);
                long otherTime = other.clock.getOrDefault(node, 0L);
                if (myTime > otherTime) return false;
                if (myTime < otherTime) strictlyLess = true;
            }
            return strictlyLess;
        }

        // Concurrent events: neither happened before the other
        boolean isConcurrentWith(VectorClock other) {
            return !happenedBefore(other) && !other.happenedBefore(this) && !equals(other);
        }

        VectorClock copy() {
            return new VectorClock(new HashMap<>(clock));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VectorClock vc && clock.equals(vc.clock);
        }

        @Override
        public String toString() {
            return clock.toString();
        }
    }

    // ==================== VERSIONED VALUE ====================

    record VersionedValue(String value, VectorClock clock, String nodeId) {}

    // ==================== EVENTUALLY CONSISTENT STORE ====================

    static class EventuallyConsistentStore {
        private final String nodeId;
        private final Map<String, VersionedValue> data = new ConcurrentHashMap<>();
        private VectorClock localClock = new VectorClock();
        private final List<Runnable> replicationListeners = new ArrayList<>();

        EventuallyConsistentStore(String nodeId) {
            this.nodeId = nodeId;
        }

        void onReplication(Runnable listener) {
            replicationListeners.add(listener);
        }

        // Write: increment local clock, store with new clock
        void write(String key, String value) {
            localClock = localClock.increment(nodeId);
            data.put(key, new VersionedValue(value, localClock.copy(), nodeId));
            System.out.printf("  [%s] WRITE key=%s value=%s clock=%s%n",
                    nodeId, key, value, localClock);
            // Async replication
            replicationListeners.forEach(Runnable::run);
        }

        Optional<VersionedValue> read(String key) {
            return Optional.ofNullable(data.get(key));
        }

        // Receive update from another node
        void receiveUpdate(String key, VersionedValue incoming) {
            VersionedValue current = data.get(key);

            if (current == null) {
                data.put(key, incoming);
                localClock = localClock.merge(incoming.clock());
                System.out.printf("  [%s] REPLICATED key=%s value=%s (no conflict)%n",
                        nodeId, key, incoming.value());
            } else if (incoming.clock().happenedBefore(current.clock())) {
                // Incoming is older - ignore it (already have newer or equal)
                System.out.printf("  [%s] IGNORED stale update key=%s (incoming clock=%s < current clock=%s)%n",
                        nodeId, key, incoming.clock(), current.clock());
            } else if (current.clock().happenedBefore(incoming.clock())) {
                // Incoming is newer - replace
                data.put(key, incoming);
                localClock = localClock.merge(incoming.clock());
                System.out.printf("  [%s] UPDATED key=%s to value=%s (incoming is newer)%n",
                        nodeId, key, incoming.value());
            } else {
                // CONCURRENT writes - CONFLICT!
                System.out.printf("  [%s] CONFLICT key=%s local=%s(%s) vs incoming=%s(%s) - merge needed%n",
                        nodeId, key, current.value(), current.clock(),
                        incoming.value(), incoming.clock());
                // Last-write-wins (simplistic) - in practice use CRDT or app-level merge
                // Higher sum of clock values = more recent activity
                long localSum = current.clock().clock.values().stream().mapToLong(Long::longValue).sum();
                long incomingSum = incoming.clock().clock.values().stream().mapToLong(Long::longValue).sum();
                VersionedValue winner = incomingSum >= localSum ? incoming : current;
                data.put(key, winner);
                localClock = localClock.merge(incoming.clock()).merge(current.clock());
                System.out.printf("  [%s] CONFLICT RESOLVED (LWW): key=%s -> value=%s%n",
                        nodeId, key, winner.value());
            }
        }

        Map<String, VersionedValue> getAllData() {
            return Collections.unmodifiableMap(data);
        }

        boolean isConsistentWith(EventuallyConsistentStore other) {
            for (String key : data.keySet()) {
                VersionedValue mine = data.get(key);
                VersionedValue theirs = other.data.get(key);
                if (theirs == null || !mine.value().equals(theirs.value())) return false;
            }
            return data.size() == other.data.size();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Eventual Consistency & Vector Clocks Demo ===");

        demonstrateVectorClocks();
        demonstrateEventualConsistency();
        demonstrateCausalOrdering();
    }

    private static void demonstrateVectorClocks() {
        System.out.println("\n--- Vector Clock Causality ---");

        VectorClock empty = new VectorClock();

        // Simulate 3 nodes exchanging messages
        VectorClock nodeA1 = empty.increment("A"); // A: [A:1]
        VectorClock nodeB1 = empty.increment("B"); // B: [B:1]
        VectorClock nodeA2 = nodeA1.increment("A"); // A: [A:2]

        // B receives message from A
        VectorClock nodeB2 = nodeB1.merge(nodeA2).increment("B"); // B: [A:2, B:2]

        // C starts and receives from B
        VectorClock nodeC1 = empty.merge(nodeB2).increment("C"); // C: [A:2, B:2, C:1]

        System.out.println("nodeA1: " + nodeA1);
        System.out.println("nodeA2: " + nodeA2);
        System.out.println("nodeB1: " + nodeB1);
        System.out.println("nodeB2: " + nodeB2);
        System.out.println("nodeC1: " + nodeC1);

        System.out.println("\nCausality analysis:");
        System.out.println("nodeA1 happened-before nodeA2: " + nodeA1.happenedBefore(nodeA2));
        System.out.println("nodeA2 happened-before nodeB2: " + nodeA2.happenedBefore(nodeB2));
        System.out.println("nodeB2 happened-before nodeC1: " + nodeB2.happenedBefore(nodeC1));
        System.out.println("nodeA1 happened-before nodeB1: " + nodeA1.happenedBefore(nodeB1));
        System.out.println("nodeA1 concurrent with nodeB1: " + nodeA1.isConcurrentWith(nodeB1));
    }

    private static void demonstrateEventualConsistency() throws InterruptedException {
        System.out.println("\n--- Eventual Consistency with 3 Nodes ---");

        EventuallyConsistentStore nodeA = new EventuallyConsistentStore("A");
        EventuallyConsistentStore nodeB = new EventuallyConsistentStore("B");
        EventuallyConsistentStore nodeC = new EventuallyConsistentStore("C");

        // Write on nodeA
        System.out.println("\n[Phase 1: Write on Node A]");
        nodeA.write("config:theme", "dark");

        // Replicate A -> B (but not C yet, simulating network delay)
        System.out.println("\n[Phase 2: Replicate A to B (C is partitioned)]");
        nodeA.getAllData().forEach((k, v) -> nodeB.receiveUpdate(k, v));

        // Concurrent write on nodeC (it thinks the value is different)
        System.out.println("\n[Phase 3: Concurrent write on C (doesn't know about A's write)]");
        nodeC.write("config:theme", "light"); // Concurrent with A's write!

        // Now replicate A -> C and C -> A (partition healed)
        System.out.println("\n[Phase 4: Partition healed - all nodes sync]");
        nodeA.getAllData().forEach((k, v) -> nodeC.receiveUpdate(k, v));
        nodeC.getAllData().forEach((k, v) -> nodeA.receiveUpdate(k, v));
        nodeA.getAllData().forEach((k, v) -> nodeB.receiveUpdate(k, v));
        nodeC.getAllData().forEach((k, v) -> nodeB.receiveUpdate(k, v));

        System.out.println("\n[Final State]");
        System.out.println("Node A: " + nodeA.read("config:theme").map(VersionedValue::value).orElse("null"));
        System.out.println("Node B: " + nodeB.read("config:theme").map(VersionedValue::value).orElse("null"));
        System.out.println("Node C: " + nodeC.read("config:theme").map(VersionedValue::value).orElse("null"));
        System.out.println("A consistent with B: " + nodeA.isConsistentWith(nodeB));
        System.out.println("A consistent with C: " + nodeA.isConsistentWith(nodeC));
        System.out.println("Eventually consistent: ALL nodes converged to same value!");
    }

    private static void demonstrateCausalOrdering() {
        System.out.println("\n--- Causal Ordering (Read-Your-Writes) ---");
        System.out.println("Problem: User posts a comment, immediately reads it,");
        System.out.println("         but reads from a replica that hasn't received the write yet.");
        System.out.println();
        System.out.println("Solutions:");
        System.out.println("1. Read-your-writes consistency:");
        System.out.println("   After write, always read from same node (sticky routing)");
        System.out.println("   Or: include write timestamp, reject reads from behind nodes");
        System.out.println();
        System.out.println("2. Causal consistency:");
        System.out.println("   Client tracks its own clock, servers only serve requests");
        System.out.println("   that have seen all causally preceding writes");
        System.out.println();
        System.out.println("3. Hybrid Logical Clocks (HLC):");
        System.out.println("   Combines physical time with logical counters");
        System.out.println("   Used by CockroachDB and YugabyteDB for global transactions");

        // Simple causal token
        AtomicInteger writeToken = new AtomicInteger(0);

        // Simulate: user writes, passes token to subsequent read
        int tokenAfterWrite = writeToken.incrementAndGet();
        System.out.println("\nSimulated causal token after write: " + tokenAfterWrite);

        // Read is rejected if replica hasn't caught up to this token
        int replicaToken = 0;
        if (replicaToken < tokenAfterWrite) {
            System.out.println("Replica not caught up (replica=" + replicaToken +
                    " < required=" + tokenAfterWrite + ") -> route to leader or wait");
        }
    }
}
