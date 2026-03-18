package com.deepdive.month03.week11;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Week 11: Consistent Hashing
 *
 * CONCEPT: Consistent hashing is a distributed hashing technique that minimizes
 * key remapping when nodes are added or removed from a cluster.
 *
 * Problem with naive hashing (key % N):
 *   When N changes (add/remove node), almost ALL keys remap to different nodes.
 *   This causes massive cache misses and thundering herds.
 *
 * Solution - Consistent Hashing:
 *   1. Map nodes to positions on a virtual "ring" (0 to 2^32)
 *   2. Map keys to positions on the same ring
 *   3. Each key is assigned to the first node clockwise on the ring
 *
 * When a node is added: Only keys between the new node and its predecessor remap
 * When a node is removed: Only keys owned by that node remap to its successor
 *
 * Average remapping: N/total_nodes keys vs (nearly all keys) with naive hashing
 *
 * Virtual nodes (vnodes):
 * Each physical node is mapped to multiple positions on the ring.
 * This ensures even load distribution and avoids "hot spots" from
 * non-uniform key distribution or node heterogeneity.
 *
 * Used by: Cassandra, DynamoDB, Chord DHT, Akka cluster, Redis Cluster,
 *          Memcached (libketama), Nginx upstream load balancing
 */
public class ConsistentHashingDemo {

    static class ConsistentHashRing {
        // TreeMap: keys are ring positions (0 to 2^32), values are node identifiers
        // TreeMap.ceilingKey() gives us O(log N) lookup for the next clockwise node
        private final TreeMap<Long, String> ring = new TreeMap<>();
        private final Map<String, List<Long>> nodePositions = new HashMap<>();
        private final int virtualNodesPerServer;

        ConsistentHashRing(int virtualNodesPerServer) {
            this.virtualNodesPerServer = virtualNodesPerServer;
        }

        /**
         * CONCEPT: Add a node to the ring.
         * Each physical node gets virtualNodesPerServer positions on the ring.
         * More virtual nodes = more even load distribution (but more memory).
         */
        void addNode(String nodeId) {
            List<Long> positions = new ArrayList<>();
            for (int i = 0; i < virtualNodesPerServer; i++) {
                // Hash "nodeId:virtualIndex" to get a ring position
                long position = hash(nodeId + ":virtual:" + i);
                ring.put(position, nodeId);
                positions.add(position);
            }
            nodePositions.put(nodeId, positions);
            System.out.printf("  Added node %s (%d virtual nodes)%n", nodeId, virtualNodesPerServer);
        }

        /**
         * CONCEPT: Remove a node from the ring.
         * Keys owned by this node are automatically reassigned to the next clockwise node.
         */
        void removeNode(String nodeId) {
            List<Long> positions = nodePositions.remove(nodeId);
            if (positions != null) {
                positions.forEach(ring::remove);
                System.out.printf("  Removed node %s%n", nodeId);
            }
        }

        /**
         * CONCEPT: Get the node responsible for a given key.
         * Find the first node clockwise from the key's hash position.
         */
        String getNode(String key) {
            if (ring.isEmpty()) throw new IllegalStateException("No nodes in ring");

            long keyHash = hash(key);
            // ceilingKey: find first position >= keyHash
            Map.Entry<Long, String> entry = ring.ceilingEntry(keyHash);
            if (entry == null) {
                // Wrap around: key is beyond last node, so it belongs to first node
                entry = ring.firstEntry();
            }
            return entry.getValue();
        }

        /**
         * CONCEPT: Get N distinct nodes for replication.
         * Walk clockwise from key position, collecting N unique nodes.
         * This is how Cassandra chooses replica nodes.
         */
        List<String> getReplicaNodes(String key, int replicationFactor) {
            if (ring.isEmpty()) throw new IllegalStateException("No nodes in ring");

            long keyHash = hash(key);
            List<String> replicas = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // Start from key's position, walk clockwise
            NavigableMap<Long, String> tail = ring.tailMap(keyHash, true);
            for (String node : tail.values()) {
                if (seen.add(node)) replicas.add(node);
                if (replicas.size() == replicationFactor) return replicas;
            }
            // Wrap around
            for (String node : ring.values()) {
                if (seen.add(node)) replicas.add(node);
                if (replicas.size() == replicationFactor) return replicas;
            }
            return replicas;
        }

        Map<String, Integer> getLoadDistribution(int numKeys) {
            Map<String, Integer> distribution = new HashMap<>();
            for (int i = 0; i < numKeys; i++) {
                String node = getNode("key:" + i);
                distribution.merge(node, 1, Integer::sum);
            }
            return distribution;
        }

        private long hash(String key) {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(key.getBytes());
                // Use first 4 bytes as unsigned long position on ring
                return ((long) (digest[0] & 0xFF) << 24) |
                       ((long) (digest[1] & 0xFF) << 16) |
                       ((long) (digest[2] & 0xFF) << 8) |
                        (long) (digest[3] & 0xFF);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        int ringSize() { return ring.size(); }
    }

    public static void main(String[] args) {
        System.out.println("=== Consistent Hashing Demo ===");

        demonstrateBasicHashing();
        demonstrateNodeAddition();
        demonstrateNodeRemoval();
        demonstrateVirtualNodes();
        demonstrateReplication();
    }

    private static void demonstrateBasicHashing() {
        System.out.println("\n--- Basic Consistent Hashing ---");
        ConsistentHashRing ring = new ConsistentHashRing(100);

        ring.addNode("cache-server-1");
        ring.addNode("cache-server-2");
        ring.addNode("cache-server-3");
        System.out.println("Ring size (virtual positions): " + ring.ringSize());

        // Assign some keys
        String[] testKeys = {"user:1001", "user:1002", "session:abc", "product:42", "cart:99"};
        System.out.println("\nKey -> Node mapping:");
        for (String key : testKeys) {
            System.out.printf("  %-20s -> %s%n", key, ring.getNode(key));
        }
    }

    private static void demonstrateNodeAddition() {
        System.out.println("\n--- Node Addition Impact ---");
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");

        int sampleSize = 1000;

        // Record original assignments
        Map<String, String> original = new HashMap<>();
        for (int i = 0; i < sampleSize; i++) {
            String key = "key:" + i;
            original.put(key, ring.getNode(key));
        }

        // Show distribution before
        System.out.println("Distribution with 3 nodes:");
        ring.getLoadDistribution(sampleSize).forEach((node, count) ->
                System.out.printf("  %s: %d keys (%.1f%%)%n",
                        node, count, (double) count / sampleSize * 100));

        // Add a new node
        System.out.println("\nAdding node-4...");
        ring.addNode("node-4");

        // Count remapped keys
        int remapped = 0;
        for (int i = 0; i < sampleSize; i++) {
            String key = "key:" + i;
            if (!ring.getNode(key).equals(original.get(key))) remapped++;
        }

        System.out.printf("Keys remapped after adding node: %d/%d (%.1f%%)%n",
                remapped, sampleSize, (double) remapped / sampleSize * 100);
        System.out.println("Expected: ~25% (1/4 of keys, with 4 nodes)");
        System.out.println("Compare: naive hash (key%N) would remap ~75% of keys!");

        System.out.println("\nDistribution with 4 nodes:");
        ring.getLoadDistribution(sampleSize).forEach((node, count) ->
                System.out.printf("  %s: %d keys (%.1f%%)%n",
                        node, count, (double) count / sampleSize * 100));
    }

    private static void demonstrateNodeRemoval() {
        System.out.println("\n--- Node Removal Impact ---");
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("node-1");
        ring.addNode("node-2");
        ring.addNode("node-3");

        int sampleSize = 1000;
        Map<String, String> original = new HashMap<>();
        for (int i = 0; i < sampleSize; i++) {
            String key = "key:" + i;
            original.put(key, ring.getNode(key));
        }

        System.out.println("Removing node-2...");
        ring.removeNode("node-2");

        int remapped = 0;
        for (int i = 0; i < sampleSize; i++) {
            String key = "key:" + i;
            if (!ring.getNode(key).equals(original.get(key))) remapped++;
        }

        System.out.printf("Keys remapped after removing node: %d/%d (%.1f%%)%n",
                remapped, sampleSize, (double) remapped / sampleSize * 100);
        System.out.println("Expected: ~33% (1/3 of keys, owned by removed node)");
        System.out.println("All remapped keys go to node-1 or node-3 (successor node)");
    }

    private static void demonstrateVirtualNodes() {
        System.out.println("\n--- Virtual Nodes for Even Distribution ---");
        int sampleSize = 10_000;
        String[] nodes = {"node-1", "node-2", "node-3", "node-4", "node-5"};

        for (int vnodes : new int[]{1, 10, 50, 150, 300}) {
            ConsistentHashRing ring = new ConsistentHashRing(vnodes);
            for (String n : nodes) ring.addNode(n);

            Map<String, Integer> dist = ring.getLoadDistribution(sampleSize);
            int min = dist.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = dist.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = (double) sampleSize / nodes.length;
            double stddev = Math.sqrt(dist.values().stream()
                    .mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));

            System.out.printf("  vnodes=%3d: min=%4d max=%4d stddev=%.1f (ideal=%.0f)%n",
                    vnodes, min, max, stddev, avg);
        }
        System.out.println("More virtual nodes = more even distribution at cost of memory.");
    }

    private static void demonstrateReplication() {
        System.out.println("\n--- Replication Factor (Cassandra-style) ---");
        ConsistentHashRing ring = new ConsistentHashRing(100);
        ring.addNode("dc1-node-1");
        ring.addNode("dc1-node-2");
        ring.addNode("dc1-node-3");
        ring.addNode("dc2-node-1");
        ring.addNode("dc2-node-2");

        String[] keys = {"user:alice", "user:bob", "order:12345"};
        for (String key : keys) {
            List<String> replicas = ring.getReplicaNodes(key, 3);
            System.out.printf("  Key %-20s -> replicas: %s%n", key, replicas);
        }
        System.out.println("With RF=3, data survives 2 node failures simultaneously.");
    }
}
