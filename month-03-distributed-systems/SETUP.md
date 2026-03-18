# Month 3 — Distributed Systems: Setup Guide

## Dependencies

**No external library dependencies** — all distributed systems concepts are implemented from scratch in pure Java 21.

```gradle
// build.gradle — nothing to uncomment
// Intentional design: implementing Raft, consistent hashing, circuit breakers
// from scratch forces deep understanding of the fundamentals.
```

This is intentional. Understanding how Raft works by writing it beats using etcd blindly.

---

## External Tools

### 1. Cassandra (optional — Week 9 & 11)
> Connect real eventual consistency and sharding demos to a live Cassandra cluster

**Docker:**
```bash
# Single node
docker run -d --name cassandra -p 9042:9042 cassandra:latest

# Wait for it to be ready (~30s)
docker exec cassandra nodetool status

# Open CQLSH
docker exec -it cassandra cqlsh
```

**Useful CQL to explore CAP trade-offs:**
```sql
-- Create keyspace with tunable consistency
CREATE KEYSPACE demo
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};

-- Write with QUORUM consistency (balanced CP)
CONSISTENCY QUORUM;
INSERT INTO demo.orders (id, status) VALUES (uuid(), 'placed');

-- Write with ANY consistency (AP — always succeeds, may lose data)
CONSISTENCY ANY;
INSERT INTO demo.orders (id, status) VALUES (uuid(), 'placed');
```

---

### 2. etcd (optional — Week 10)
> Real distributed consensus (Raft-based) to compare against `RaftConsensusDemo`

**Docker:**
```bash
docker run -d \
  --name etcd \
  -p 2379:2379 \
  quay.io/coreos/etcd:v3.5.11 \
  etcd \
  --advertise-client-urls http://0.0.0.0:2379 \
  --listen-client-urls http://0.0.0.0:2379

# CLI interaction
docker exec etcd etcdctl put /config/feature-flag "true"
docker exec etcd etcdctl get /config/feature-flag
docker exec etcd etcdctl watch /config/feature-flag
```

---

### 3. MongoDB (optional — Week 11)
> Explore real database sharding alongside `DatabaseShardingDemo`

**Docker:**
```bash
docker run -d --name mongo -p 27017:27017 mongo:7.0

# Open Mongo shell
docker exec -it mongo mongosh

# Enable sharding on a collection
use admin
sh.enableSharding("demo")
sh.shardCollection("demo.orders", { "customerId": "hashed" })
```

---

### 4. Resilience4j (optional — Week 12)
> Add real circuit breaker to your code alongside `CircuitBreakerDemo`

**Add to build.gradle:**
```gradle
dependencies {
    implementation 'io.github.resilience4j:resilience4j-core:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-retry:2.2.0'
    implementation 'io.github.resilience4j:resilience4j-bulkhead:2.2.0'
}
```

**Quick usage:**
```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)
    .waitDurationInOpenState(Duration.ofSeconds(10))
    .slidingWindowSize(10)
    .build();

CircuitBreaker cb = CircuitBreaker.of("payment", config);
Supplier<String> decorated = CircuitBreaker.decorateSupplier(cb, this::callPaymentService);
```

---

### 5. NGINX (optional — Week 12)
> Real load balancer to compare against `LoadBalancerDemo` algorithms

**Docker:**
```bash
docker run -d \
  --name nginx-lb \
  -p 80:80 \
  -v $(pwd)/nginx.conf:/etc/nginx/nginx.conf \
  nginx:latest
```

**nginx.conf — Round Robin (default):**
```nginx
http {
    upstream backend {
        server localhost:8081;
        server localhost:8082;
        server localhost:8083;
    }
    server {
        listen 80;
        location / { proxy_pass http://backend; }
    }
}
```

**nginx.conf — Least Connections:**
```nginx
upstream backend {
    least_conn;
    server localhost:8081;
    server localhost:8082;
}
```

**nginx.conf — Weighted Round Robin:**
```nginx
upstream backend {
    server localhost:8081 weight=3;
    server localhost:8082 weight=1;
}
```

---

## Quick Start

```bash
# No setup needed — all demos are self-contained

# Run CAP theorem demo
./gradlew :month-03-distributed-systems:run \
  -PmainClass=com.deepdive.month03.week09.CapTheoremDemo

# Run Raft consensus demo (simulated multi-node election)
./gradlew :month-03-distributed-systems:run \
  -PmainClass=com.deepdive.month03.week10.RaftConsensusDemo

# Run circuit breaker demo
./gradlew :month-03-distributed-systems:run \
  -PmainClass=com.deepdive.month03.week12.CircuitBreakerDemo
```
