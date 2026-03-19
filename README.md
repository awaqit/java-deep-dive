# Java Deep Dive — Staff Engineer Learning Journey

A 6-month, hands-on Java project following the **Staff Engineer Learning Schedule** (Netflix/Google level).
Each week maps to runnable Java examples with educational comments explaining the **what**, **why**, and **how**.

## Structure

```
java-deep-dive/
├── month-01-java-foundations/          ← JVM, Concurrency, NIO
├── month-02-cloud-native/              ← Microservices, Kafka, gRPC, CQRS
├── month-03-distributed-systems/       ← CAP, Raft, Sharding, Circuit Breakers
├── month-04-kubernetes/                ← K8s client, Graceful shutdown, RBAC
├── month-05-real-time-systems/         ← Kafka Streams, Reactive, Disruptor
└── month-06-security-leadership/       ← JWT, AES/RSA, Zero Trust, ADRs
```

## Prerequisites

- Java 21+
- Gradle 8+
- Docker (for Kafka, Kubernetes, Vault, Keycloak)

## External Tools & Dependencies

Each module has a dedicated `SETUP.md` with full installation instructions, Docker commands, and configuration details.

| Module | External Tools | Dependencies | Setup Guide |
|--------|---------------|--------------|-------------|
| Month 1 — Java Foundations | JDK Mission Control, VisualVM, JITWatch, async-profiler | None (pure Java 21) | [SETUP.md](month-01-java-foundations/SETUP.md) |
| Month 2 — Cloud-Native | Apache Kafka, Kafka UI, gRPC / protoc | Spring Boot, spring-kafka, grpc-netty | [SETUP.md](month-02-cloud-native/SETUP.md) |
| Month 3 — Distributed Systems | Cassandra, etcd, MongoDB, NGINX, Resilience4j | None (pure Java 21) | [SETUP.md](month-03-distributed-systems/SETUP.md) |
| Month 4 — Kubernetes | minikube / kind, kubectl, Helm, HashiCorp Vault, Lens | fabric8 kubernetes-client | [SETUP.md](month-04-kubernetes/SETUP.md) |
| Month 5 — Real-Time Systems | Apache Kafka, async-profiler, Grafana + Prometheus + Loki, Jaeger (tracing) | reactor-core, kafka-streams, spring-webflux, micrometer, opentelemetry-sdk | [SETUP.md](month-05-real-time-systems/SETUP.md) |
| Month 6 — Security | HashiCorp Vault, Keycloak, OpenSSL, OWASP ZAP, Trivy | BouncyCastle, JJWT, spring-security | [SETUP.md](month-06-security-leadership/SETUP.md) |

## How to Run

```bash
# Build all modules
./gradlew build

# Run a specific demo (example)
./gradlew :month-01-java-foundations:run -PmainClass=com.deepdive.month01.week03.ForkJoinDemo

# Compile a single module
./gradlew :month-01-java-foundations:compileJava
```

---

## Month-by-Month Plan

### Month 1 — Java Foundations
> Goal: Master JVM internals, concurrency, and non-blocking I/O

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 1 | JVM Memory Management | `GarbageCollectionDemo`, `HeapMemoryAnalysis`, `MemoryLeakExample` |
| Week 2 | JVM Performance & JIT | `JitCompilationDemo`, `HotSpotProfilerExample` |
| Week 3 | Concurrency | `ThreadPoolDemo`, `ProducerConsumerDemo`, `ForkJoinDemo`, `AtomicVariablesDemo` |
| Week 4 | Non-blocking I/O (NIO) | `NioServerDemo`, `NioFileDemo` |

**JVM flags to try with Week 1:**
```
-Xms256m -Xmx512m -XX:+UseG1GC -verbose:gc
```

---

### Month 2 — Cloud-Native & Microservices
> Goal: Event-driven systems, CQRS, Kafka, API design

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 5 | Microservices Patterns | `CqrsDemo`, `EventSourcingDemo`, `SagaPatternDemo` |
| Week 6 | Service Mesh & gRPC | `GrpcConceptsDemo` |
| Week 7 | Event-Driven with Kafka | `KafkaProducerDemo`, `KafkaConsumerDemo` |
| Week 8 | API Versioning | `ApiVersioningDemo` |

**Note:** Kafka demos require a running Kafka instance. Use Docker:
```bash
docker run -d -p 9092:9092 apache/kafka:latest
```

---

### Month 3 — Distributed Systems
> Goal: CAP theorem, consensus algorithms, fault tolerance

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 9  | CAP Theorem | `CapTheoremDemo`, `EventualConsistencyDemo` |
| Week 10 | Distributed Consensus (Raft) | `RaftConsensusDemo` |
| Week 11 | Sharding & Consistent Hashing | `ConsistentHashingDemo`, `DatabaseShardingDemo` |
| Week 12 | Fault Tolerance | `CircuitBreakerDemo`, `RetryPatternDemo`, `LoadBalancerDemo` |

---

### Month 4 — Kubernetes
> Goal: Kubernetes internals from the Java application perspective

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 13 | Kubernetes Client | `KubernetesClientDemo` |
| Week 14 | Stateful Apps | `GracefulShutdownDemo`, `HealthCheckDemo` |
| Week 15 | Service Discovery | `ServiceDiscoveryDemo` |
| Week 16 | Security & RBAC | `RbacConceptsDemo`, `SecretsManagementDemo` |

---

### Month 5 — Real-Time Systems
> Goal: Stream processing, reactive programming, high-performance patterns, observability

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 17 | Kafka Streams | `KafkaStreamsDemo` |
| Week 18 | Reactive Programming | `ReactiveStreamsDemo`, `BackpressureDemo` |
| Week 19 | Reactive Web Client + Observability | `ReactiveWebClientDemo`, `ObservabilityDemo` |
| Week 20 | High-Performance Patterns | `HighFrequencyDemo`, `DisruptorPatternDemo` |

**Observability topics covered in Week 19 (added):**
- **Micrometer** — metrics instrumentation (counters, timers, gauges) exported to Prometheus
- **OpenTelemetry** — distributed tracing across service boundaries (spans, trace context propagation)
- **Structured logging** — JSON logs with trace/span IDs for correlation in Grafana/Loki
- **Key questions to answer:** How do you measure Kafka consumer lag? How do you trace a request across 5 microservices? What SLOs do you put on a reactive pipeline?

---

### Month 6 — Security & Leadership
> Goal: Cryptography, zero trust architecture, tech leadership

| Week | Topic | Key Files |
|------|-------|-----------|
| Week 21 | Zero Trust & JWT | `JwtDemo`, `OAuthConceptsDemo` |
| Week 22 | Encryption | `AesEncryptionDemo`, `RsaEncryptionDemo` |
| Week 23 | Design Patterns at Scale | `DesignPatternsDemo` |
| Week 24 | Tech Leadership | `TechLeadershipPatterns` |

---

## Learning Philosophy

Each demo file follows this pattern:
- **`// CONCEPT:`** — What the pattern/technology is
- **`// WHY:`** — Why it matters at Staff Engineer level
- **`// NOTE:`** — Gotchas and production considerations

The goal is not just to run the code — it's to **understand the trade-offs** well enough to explain and defend decisions in a system design interview or RFC review.

---

## Roadmap

- [ ] Month 1: JVM & Concurrency fundamentals
- [ ] Month 2: Cloud-native patterns
- [ ] Month 3: Distributed systems
- [ ] Month 4: Kubernetes integration
- [ ] Month 5: Real-time & reactive
- [ ] Month 6: Security & leadership

---

## Learning Resources

### General — Staff Engineer Foundation

| Resource | Type | Why It Matters |
|----------|------|----------------|
| [Staff Engineer](https://staffeng.com/book) — Will Larson | Book | Defines Staff+ archetypes, influence, and career trajectory |
| [An Elegant Puzzle](https://lethain.com/elegant-puzzle/) — Will Larson | Book | Engineering management and org design from a staff perspective |
| [The Staff Engineer's Path](https://www.oreilly.com/library/view/the-staff-engineers/9781098118723/) — Tanya Reilly | Book | Day-to-day patterns: technical direction, RFC writing, mentoring |
| [staffeng.com — Stories](https://staffeng.com/stories) | Articles | Real accounts of Staff Engineers at Stripe, Dropbox, GitHub, etc. |

---

### Month 1 — Java Foundations (JVM, Concurrency, NIO)

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Understanding the JVM: Memory Management | [Open in Notion](https://www.notion.so/Understanding-the-JVM-Memory-Management-2ea0043be7f280fc8d85fbf06eaf9af5?pvs=21) |
| Understanding & Solving Java Memory Problems | [Open in Notion](https://www.notion.so/Understanding-Solving-Java-Memory-Problems-2ea0043be7f28083901bfffacc417e7d?pvs=21) |
| Java Concurrency in Practice | [Open in Notion](https://www.notion.so/Java-Concurrency-in-Practice-31e0043be7f281fd90e3eb1e3a353b1f?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Java Concurrency in Practice](https://jcip.net/) — Goetz et al. | Book | The definitive guide to Java threading, locks, and memory model |
| [Java Performance](https://www.oreilly.com/library/view/java-performance-2nd/9781492056812/) — Scott Oaks | Book | GC tuning, JIT internals, profiling, benchmarking with JMH |
| [JEP 444: Virtual Threads (Project Loom)](https://openjdk.org/jeps/444) | JEP | Virtual threads — the why, how, and limitations |
| [Aleksey Shipilev's Blog](https://shipilev.net/) | Blog | Deep JVM internals: false sharing, JIT, GC, benchmarking pitfalls |
| [G1GC Tuning Guide](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html) | Docs | Official GC tuning reference for G1, ZGC, Shenandoah |
| [Inside the Java NIO.2 API](https://openjdk.org/projects/nio/) | Docs | Async file I/O, selectors, and channels spec |
| [JVM Anatomy Quarks](https://shipilev.net/jvm/anatomy-quarks/) — Shipilev | Articles | Bite-sized deep dives into JVM internals (escape analysis, barriers, etc.) |

---

### Month 2 — Cloud-Native & Microservices

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Apache Kafka for Developers | [Open in Notion](https://www.notion.so/Apache-Kafka-for-Developers-31e0043be7f281cba0b0d2134c20aa1c?pvs=21) |
| OpenAPI Specification & Swagger Tools | [Open in Notion](https://www.notion.so/OpenAPI-Specification-Swagger-Tools-2ea0043be7f2808ca30be8cbead1f562?pvs=21) |
| Data Transactions with Spring | [Open in Notion](https://www.notion.so/Data-Transactions-with-Spring-2ea0043be7f280199e7ae523bbab561a?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Building Microservices](https://samnewman.io/books/building_microservices_2nd_edition/) — Sam Newman | Book | Service decomposition, SAGA, API design, service mesh |
| [Designing Event-Driven Systems](https://www.confluent.io/designing-event-driven-systems/) — Ben Stopford | Book (free) | Kafka, CQRS, Event Sourcing — free PDF from Confluent |
| [Martin Fowler — CQRS](https://martinfowler.com/bliki/CQRS.html) | Article | Canonical explanation of the CQRS pattern |
| [Martin Fowler — Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html) | Article | Event Sourcing definition and trade-offs |
| [Saga Pattern — Chris Richardson](https://microservices.io/patterns/data/saga.html) | Article | Orchestration vs choreography SAGA with failure scenarios |
| [Kafka Documentation](https://kafka.apache.org/documentation/) | Docs | Producer configs, consumer groups, offset management, transactions |
| [gRPC Concepts](https://grpc.io/docs/what-is-grpc/core-concepts/) | Docs | 4 call types, deadlines, metadata, interceptors |
| [Google API Design Guide](https://cloud.google.com/apis/design) | Guide | REST resource naming, versioning, error models used at Google |

---

### Month 3 — Distributed Systems

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Distributed Systems & Cloud Computing | [Open in Notion](https://www.notion.so/Distributed-Systems-Cloud-Computing-31e0043be7f281619178f96778c859ec?pvs=21) |
| Fundamentals of Database Engineering | [Open in Notion](https://www.notion.so/Fundamentals-of-Database-Engineering-2ea0043be7f280379400e4e3f9ade025?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Designing Data-Intensive Applications](https://dataintensive.net/) — Martin Kleppmann | Book | **The** distributed systems book — CAP, replication, consensus, Kafka |
| [Raft Consensus Algorithm Paper](https://raft.github.io/raft.pdf) — Ongaro & Ousterhout | Paper | Original Raft paper — leader election, log replication, safety proofs |
| [Amazon Dynamo Paper](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf) | Paper | Consistent hashing, vector clocks, eventual consistency at AWS |
| [Google Spanner Paper](https://research.google/pubs/pub39966/) | Paper | Globally distributed SQL with TrueTime — CP at global scale |
| [CAP Theorem — Brewer's Original Talk](https://people.eecs.berkeley.edu/~brewer/cs262b-2004/PODC-keynote.pdf) | Paper | The original CAP conjecture and what it actually means |
| [MIT 6.5840 Distributed Systems](https://pdos.csail.mit.edu/6.824/) | Course | MIT's graduate course — labs in Go, lectures free on YouTube |
| [Resilience4j Docs](https://resilience4j.readme.io/docs) | Docs | Circuit breaker, retry, bulkhead configuration reference |
| [microservices.io Patterns](https://microservices.io/patterns/index.html) | Reference | Chris Richardson's pattern catalog — circuit breaker, SAGA, outbox |

---

### Month 4 — Kubernetes

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Kubernetes for Stateful Applications | [Open in Notion](https://www.notion.so/Kubernetes-for-Stateful-Applications-31e0043be7f281bc814cd4322c4f3a41?pvs=21) |
| Managing Kubernetes Clusters with Lens | [Open in Notion](https://www.notion.so/Managing-Kubernetes-Clusters-with-Lens-2ea0043be7f2801daf8af60c2ed5c779?pvs=21) |
| Packaging Applications with Helm | [Open in Notion](https://www.notion.so/Packaging-Applications-with-Helm-2ea0043be7f28009a8badb64c3b5ccb5?pvs=21) |
| GitOps (Linux Foundation) | [Open in Notion](https://www.notion.so/GitOps-Linux-Foundation-2ea0043be7f2815ca29de3b6fdd6856e?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Kubernetes in Action](https://www.manning.com/books/kubernetes-in-action-second-edition) — Marko Lukša | Book | K8s internals from the app developer perspective |
| [Programming Kubernetes](https://www.oreilly.com/library/view/programming-kubernetes/9781492047094/) — Hausenblas & Schimanski | Book | Writing operators, controllers, and CRDs in Go (concepts apply to Java) |
| [Kubernetes Documentation](https://kubernetes.io/docs/home/) | Docs | Official reference for RBAC, Secrets, health probes, graceful shutdown |
| [fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) | Docs | Java client API for interacting with K8s clusters |
| [Kubernetes Failure Stories](https://k8s.af/) | Case Studies | Real production outages — invaluable for understanding failure modes |
| [CKAD Curriculum](https://github.com/cncf/curriculum) | Certification | Official exam topics — validates practical K8s application knowledge |
| [Graceful Shutdown Patterns](https://learnk8s.io/graceful-shutdown) | Article | How preStop hooks and terminationGracePeriodSeconds interact |

---

### Month 5 — Real-Time Systems

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Apache Kafka for Developers | [Open in Notion](https://www.notion.so/Apache-Kafka-for-Developers-31e0043be7f281cba0b0d2134c20aa1c?pvs=21) |
| DevOps and SRE Fundamentals: Implementing Continuous Delivery | [Open in Notion](https://www.notion.so/DevOps-and-SRE-Fundamentals-Implementing-Continuous-Delivery-2ea0043be7f28184a06ecc751bb844f9?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Kafka: The Definitive Guide](https://www.confluent.io/resources/kafka-the-definitive-guide-v2/) — Narkhede et al. | Book (free) | Kafka Streams, consumer lag, stream-table joins, windowing |
| [Reactive Design Patterns](https://www.reactivedesignpatterns.com/) — Roland Kuhn | Book | Backpressure, supervision, reactive streams specification |
| [Project Reactor Reference](https://projectreactor.io/docs/core/release/reference/) | Docs | Mono/Flux operators, schedulers, error handling, testing |
| [LMAX Disruptor Technical Paper](https://lmax-exchange.github.io/disruptor/disruptor.html) | Paper | Ring buffer design, mechanical sympathy, sequence barriers |
| [Reactive Streams Specification](https://www.reactive-streams.org/) | Spec | The 4-interface contract that underpins Reactor, RxJava, Akka Streams |
| [Martin Thompson — Mechanical Sympathy Blog](https://mechanical-sympathy.blogspot.com/) | Blog | False sharing, cache lines, lock-free algorithms, CPU affinity |
| [Kafka Streams Developer Guide](https://kafka.apache.org/documentation/streams/) | Docs | KStream/KTable, windowed aggregations, exactly-once semantics |

---

### Month 6 — Security & Leadership

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Zero Trust Security Architecture | [Open in Notion](https://www.notion.so/Zero-Trust-Security-Architecture-31e0043be7f281fc8f34d364a5b99a6e?pvs=21) |
| Implementing DevSecOps (LFS262) | [Open in Notion](https://www.notion.so/Implementing-DevSecOps-LFS262-2ea0043be7f281f7a385e1e05c3f0f24?pvs=21) |
| Web Security and the OWASP Top 10: The Big Picture | [Open in Notion](https://www.notion.so/Web-Security-and-the-OWASP-Top-10-The-Big-Picture-2ea0043be7f28160af93c4649ca4b66f?pvs=21) |
| ICAgile: Leading Technical Teams | [Open in Notion](https://www.notion.so/ICAgile-Leading-Technical-Teams-2ea0043be7f281ee9782d47c8931440f?pvs=21) |

| Resource | Type | Covers |
|----------|------|--------|
| [Cryptography Engineering](https://www.schneier.com/books/cryptography-engineering/) — Ferguson, Schneier, Kohno | Book | AES-GCM, RSA, ECDSA, key management — practical crypto for engineers |
| [OAuth 2.0 in Action](https://www.manning.com/books/oauth-2-in-action) — Richer & Sanso | Book | Authorization Code + PKCE, token introspection, security threats |
| [JWT RFC 7519](https://datatracker.ietf.org/doc/html/rfc7519) | RFC | The JWT specification — claims, signing, validation rules |
| [OAuth 2.0 RFC 6749](https://datatracker.ietf.org/doc/html/rfc6749) | RFC | The OAuth 2.0 authorization framework |
| [OWASP Top 10](https://owasp.org/www-project-top-ten/) | Reference | The 10 most critical web application security risks |
| [OWASP API Security Top 10](https://owasp.org/www-project-api-security/) | Reference | API-specific risks: broken object-level auth, excessive data exposure |
| [Zero Trust Architecture — NIST SP 800-207](https://csrc.nist.gov/publications/detail/sp/800-207/final) | Standard | NIST's formal definition of Zero Trust principles |
| [Architecture Decision Records — Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) | Article | The original ADR proposal — lightweight, living documentation |
| [Google SRE Book](https://sre.google/sre-book/table-of-contents/) | Book (free) | SLOs, error budgets, toil elimination, postmortem culture |

---

### Ongoing — Run Anytime

#### 📚 Supplement Courses (from Notion plan)

| Course | Link |
|--------|------|
| Comprehensive Azure DevOps Course | [Open in Notion](https://www.notion.so/Comprehensive-Azure-DevOps-Course-2ea0043be7f280f998b2fadbae7d603c?pvs=21) |
| Getting Started with Jenkins | [Open in Notion](https://www.notion.so/Getting-Started-with-Jenkins-2ea0043be7f280f88b8cedaa1f330532?pvs=21) |
| Site Reliability Engineering: The Big Picture | [Open in Notion](https://www.notion.so/Site-Reliability-Engineering-The-Big-Picture-2ea0043be7f2813d8f8eda34ef97411e?pvs=21) |
| Generative AI Foundations: Prompt Engineering | [Open in Notion](https://www.notion.so/Generative-AI-Foundations-Prompt-Engineering-2ea0043be7f28026a7cad3ce80c57445?pvs=21) |
| Mastering ChatGPT: Unleash AI for Professional Excellence | [Open in Notion](https://www.notion.so/Mastering-ChatGPT-Unleash-AI-for-Professional-Excellence-2ea0043be7f2802b84f0c4f575ccc3c6?pvs=21) |
