package com.deepdive.month03.week11;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Week 11: Database Sharding Strategies
 *
 * CONCEPT: Sharding (horizontal partitioning) splits data across multiple database
 * nodes (shards) to scale beyond what a single node can handle.
 *
 * When to shard:
 * - Table > 100GB (read/write latency degrades)
 * - Write throughput exceeds single node capacity
 * - Single region isn't enough (geographic distribution)
 *
 * Sharding challenges:
 * - Joins across shards are expensive (must scatter-gather)
 * - Transactions across shards require 2PC or SAGA (complex)
 * - Rebalancing shards is operationally hard
 * - Schema changes must be applied to all shards
 *
 * Sharding key selection (critical decision):
 * - Low cardinality (e.g., country_code): leads to hotspots
 * - Sequential IDs: writes always go to the same shard (hotspot)
 * - Random hash: even distribution, but range queries require scatter-gather
 * - Composite key: balance between locality and distribution
 *
 * Sharding strategies:
 * 1. Range-based:      [1-1000] -> shard1, [1001-2000] -> shard2
 * 2. Hash-based:       hash(key) % N -> shard index
 * 3. Directory-based:  Lookup table maps key -> shard
 * 4. Geographic:       user's region -> closest shard
 */
public class DatabaseShardingDemo {

    // Simulate a database shard
    static class DatabaseShard {
        final String shardId;
        final Map<String, Map<String, Object>> tables = new HashMap<>();
        int writeCount = 0;
        int readCount = 0;

        DatabaseShard(String shardId) {
            this.shardId = shardId;
        }

        void insert(String table, String key, Map<String, Object> row) {
            tables.computeIfAbsent(table, k -> new ConcurrentHashMap<>()).put(key, row);
            writeCount++;
        }

        Map<String, Object> find(String table, String key) {
            readCount++;
            return tables.getOrDefault(table, Collections.emptyMap()).get(key);
        }

        List<Map<String, Object>> scan(String table) {
            readCount++;
            return new ArrayList<>(tables.getOrDefault(table, Collections.emptyMap()).values());
        }

        int rowCount(String table) {
            return tables.getOrDefault(table, Collections.emptyMap()).size();
        }
    }

    // ==================== RANGE-BASED SHARDING ====================

    static class RangeShardRouter {
        record ShardRange(long from, long to, DatabaseShard shard) {}
        private final List<ShardRange> ranges = new ArrayList<>();

        void addShard(long from, long to, DatabaseShard shard) {
            ranges.add(new ShardRange(from, to, shard));
        }

        DatabaseShard routeById(long id) {
            for (ShardRange range : ranges) {
                if (id >= range.from() && id <= range.to()) return range.shard();
            }
            throw new IllegalArgumentException("No shard for id: " + id);
        }

        // Range queries are efficient: only hit relevant shards
        List<DatabaseShard> routeRange(long fromId, long toId) {
            Set<DatabaseShard> shards = new LinkedHashSet<>();
            for (ShardRange range : ranges) {
                if (fromId <= range.to() && toId >= range.from()) {
                    shards.add(range.shard());
                }
            }
            return new ArrayList<>(shards);
        }
    }

    // ==================== HASH-BASED SHARDING ====================

    static class HashShardRouter {
        private final List<DatabaseShard> shards;

        HashShardRouter(List<DatabaseShard> shards) {
            this.shards = shards;
        }

        DatabaseShard routeByKey(String key) {
            int shardIndex = Math.abs(key.hashCode()) % shards.size();
            return shards.get(shardIndex);
        }

        // Hash sharding: range queries require scatter-gather (hit all shards)
        List<DatabaseShard> getAllShards() {
            return Collections.unmodifiableList(shards);
        }
    }

    // ==================== DIRECTORY-BASED SHARDING ====================
    // CONCEPT: A directory service maps entity IDs to specific shards.
    // Flexible: can rebalance without rehashing all keys.
    // Cost: Directory is a single point of failure (mitigated by caching).

    static class DirectoryShardRouter {
        private final Map<String, DatabaseShard> directory = new ConcurrentHashMap<>();
        private final List<DatabaseShard> shards;
        private int nextShard = 0;

        DirectoryShardRouter(List<DatabaseShard> shards) {
            this.shards = shards;
        }

        DatabaseShard getOrAssignShard(String entityId) {
            return directory.computeIfAbsent(entityId, k -> {
                // Round-robin assignment (could be based on load, geography, etc.)
                DatabaseShard shard = shards.get(nextShard % shards.size());
                nextShard++;
                return shard;
            });
        }

        // Rebalance: move entity from one shard to another
        void migrateShard(String entityId, DatabaseShard targetShard) {
            DatabaseShard source = directory.get(entityId);
            if (source != null && source != targetShard) {
                // Copy data from source to target
                Map<String, Object> row = source.find("entities", entityId);
                if (row != null) {
                    targetShard.insert("entities", entityId, row);
                    directory.put(entityId, targetShard);
                    System.out.printf("  Migrated entity %s from %s to %s%n",
                            entityId, source.shardId, targetShard.shardId);
                }
            }
        }
    }

    // ==================== CROSS-SHARD OPERATIONS ====================

    static class ShardedOrderDatabase {
        private final HashShardRouter userRouter;
        private final HashShardRouter orderRouter;

        ShardedOrderDatabase(int numShards) {
            List<DatabaseShard> userShards = new ArrayList<>();
            List<DatabaseShard> orderShards = new ArrayList<>();
            for (int i = 0; i < numShards; i++) {
                userShards.add(new DatabaseShard("user-shard-" + i));
                orderShards.add(new DatabaseShard("order-shard-" + i));
            }
            this.userRouter = new HashShardRouter(userShards);
            this.orderRouter = new HashShardRouter(orderShards);
        }

        void createUser(String userId, String name, String email) {
            DatabaseShard shard = userRouter.routeByKey(userId);
            shard.insert("users", userId, Map.of("id", userId, "name", name, "email", email));
        }

        void createOrder(String orderId, String userId, double amount) {
            // WHY: Order shard is based on orderId (not userId)
            // Orders for same user may be on different shards!
            // If you need "orders by user", consider sharding by userId instead.
            DatabaseShard shard = orderRouter.routeByKey(orderId);
            shard.insert("orders", orderId, Map.of(
                    "orderId", orderId, "userId", userId, "amount", amount));
        }

        // CONCEPT: Cross-shard join - scatter-gather
        // This is expensive: O(N shards) parallel queries + merge
        Map<String, Object> getUserWithOrders(String userId) {
            // Get user from user shard
            Map<String, Object> user = userRouter.routeByKey(userId).find("users", userId);
            if (user == null) return null;

            // Scan ALL order shards to find orders for this user
            // WHY: Orders are sharded by orderId, not userId - we must scan all shards
            List<Map<String, Object>> userOrders = new ArrayList<>();
            for (DatabaseShard shard : orderRouter.getAllShards()) {
                shard.scan("orders").stream()
                        .filter(order -> userId.equals(order.get("userId")))
                        .forEach(userOrders::add);
            }

            Map<String, Object> result = new HashMap<>(user);
            result.put("orders", userOrders);
            result.put("scannedShards", orderRouter.getAllShards().size());
            return result;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== Database Sharding Demo ===");

        demonstrateRangeSharding();
        demonstrateHashSharding();
        demonstrateDirectorySharding();
        demonstrateCrossShardJoin();
        discussShardingPitfalls();
    }

    private static void demonstrateRangeSharding() {
        System.out.println("\n--- Range-Based Sharding ---");
        DatabaseShard shard1 = new DatabaseShard("shard-1");
        DatabaseShard shard2 = new DatabaseShard("shard-2");
        DatabaseShard shard3 = new DatabaseShard("shard-3");

        RangeShardRouter router = new RangeShardRouter();
        router.addShard(1, 1000, shard1);
        router.addShard(1001, 2000, shard2);
        router.addShard(2001, 3000, shard3);

        // Insert users
        for (long id = 1; id <= 3000; id += 100) {
            router.routeById(id).insert("users", String.valueOf(id),
                    Map.of("id", id, "name", "User-" + id));
        }

        System.out.println("Shard distribution:");
        System.out.printf("  shard-1 (1-1000):     %d rows, %d writes%n",
                shard1.rowCount("users"), shard1.writeCount);
        System.out.printf("  shard-2 (1001-2000):  %d rows, %d writes%n",
                shard2.rowCount("users"), shard2.writeCount);
        System.out.printf("  shard-3 (2001-3000):  %d rows, %d writes%n",
                shard3.rowCount("users"), shard3.writeCount);

        // Range query only touches 2 shards
        List<DatabaseShard> shardsForRange = router.routeRange(500, 1500);
        System.out.printf("%nRange query (500-1500): hits %d shards: %s%n",
                shardsForRange.size(),
                shardsForRange.stream().map(s -> s.shardId).toList());

        System.out.println("Advantage: Range queries are efficient");
        System.out.println("Weakness: New users always go to last shard (hotspot!)");
        System.out.println("Solution: Use UUID or random shard prefix for write distribution");
    }

    private static void demonstrateHashSharding() {
        System.out.println("\n--- Hash-Based Sharding ---");
        List<DatabaseShard> shards = List.of(
                new DatabaseShard("hash-shard-0"),
                new DatabaseShard("hash-shard-1"),
                new DatabaseShard("hash-shard-2")
        );
        HashShardRouter router = new HashShardRouter(shards);

        // Insert users with UUID-style IDs (typical in microservices)
        String[] userIds = {"usr-a1b2", "usr-c3d4", "usr-e5f6", "usr-g7h8", "usr-i9j0",
                "usr-k1l2", "usr-m3n4", "usr-o5p6", "usr-q7r8", "usr-s9t0"};
        for (String id : userIds) {
            router.routeByKey(id).insert("users", id, Map.of("id", id));
        }

        System.out.println("Distribution:");
        for (DatabaseShard shard : shards) {
            System.out.printf("  %s: %d rows%n", shard.shardId, shard.rowCount("users"));
        }
        System.out.println("Advantage: Even write distribution, no hotspots");
        System.out.println("Weakness: Range queries must scatter-gather (all shards)");
    }

    private static void demonstrateDirectorySharding() {
        System.out.println("\n--- Directory-Based Sharding ---");
        List<DatabaseShard> shards = List.of(
                new DatabaseShard("dir-shard-A"),
                new DatabaseShard("dir-shard-B")
        );
        DirectoryShardRouter router = new DirectoryShardRouter(shards);

        // Assign entities to shards via directory
        String[] tenantIds = {"tenant-netflix", "tenant-spotify", "tenant-uber",
                "tenant-lyft", "tenant-airbnb"};
        for (String tenantId : tenantIds) {
            DatabaseShard shard = router.getOrAssignShard(tenantId);
            shard.insert("tenants", tenantId, Map.of("id", tenantId));
            System.out.printf("  Assigned %s -> %s%n", tenantId, shard.shardId);
        }

        // Migrate one tenant (common in multi-tenant SaaS: high-value tenants get dedicated shards)
        DatabaseShard vipShard = new DatabaseShard("vip-shard");
        router.migrateShard("tenant-netflix", vipShard);
    }

    private static void demonstrateCrossShardJoin() {
        System.out.println("\n--- Cross-Shard Join (Scatter-Gather) ---");
        ShardedOrderDatabase db = new ShardedOrderDatabase(3);

        db.createUser("user-alice", "Alice Smith", "alice@example.com");
        db.createOrder("order-1001", "user-alice", 99.99);
        db.createOrder("order-1002", "user-alice", 149.99);
        db.createOrder("order-1003", "user-alice", 29.99);

        Map<String, Object> result = db.getUserWithOrders("user-alice");
        if (result != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> orders = (List<Map<String, Object>>) result.get("orders");
            System.out.printf("User: %s, Orders: %d, Shards scanned: %s%n",
                    result.get("name"), orders.size(), result.get("scannedShards"));
        }
        System.out.println("WARNING: Cross-shard joins scale poorly! O(N*shards) queries.");
        System.out.println("Solution: Shard orders by userId for user-centric queries.");
        System.out.println("          Or: Denormalize/materialize the join view.");
    }

    private static void discussShardingPitfalls() {
        System.out.println("\n--- Sharding Pitfalls ---");
        System.out.println("1. Hotspot shards: Monotonic IDs, popular keys -> rebalancing required");
        System.out.println("2. Cross-shard transactions: Use SAGA (month 2) or accept eventual consistency");
        System.out.println("3. Cross-shard joins: Denormalize, use document DBs, or accept scatter-gather");
        System.out.println("4. Schema migrations: Must apply to all shards simultaneously");
        System.out.println("5. Rebalancing: Adding a shard requires moving data (consistent hashing helps)");
        System.out.println();
        System.out.println("Staff Engineer recommendation:");
        System.out.println("  Try to delay sharding as long as possible (complexity cost is high).");
        System.out.println("  First: vertical scaling, read replicas, caching, query optimization.");
        System.out.println("  When unavoidable: NewSQL (CockroachDB, Spanner) handles it transparently.");
    }
}
