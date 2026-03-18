# Month 1 — Java Foundations: Setup Guide

## Dependencies

**No external library dependencies** — all demos run on pure Java 21 standard library.

```gradle
// build.gradle — nothing to uncomment
// Java 21 features used: Virtual Threads, Records, Sealed Classes, Pattern Matching
```

---

## External Tools

### 1. JDK Mission Control (JMC) + Java Flight Recorder (JFR)
> Used with: `GarbageCollectionDemo`, `HotSpotProfilerExample`, `JitCompilationDemo`

**Install:**
```bash
# macOS (Homebrew)
brew install --cask jdk-mission-control

# Or download directly from:
# https://adoptium.net/jmc/
```

**Usage:**
```bash
# Run a demo with JFR recording
java -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=30s,filename=recording.jfr \
     -cp build/classes/java/main \
     com.deepdive.month01.week01.GarbageCollectionDemo

# Open recording in JMC
jmc recording.jfr
```

---

### 2. VisualVM
> Used with: `HeapMemoryAnalysis`, `MemoryLeakExample` — live heap inspection and GC monitoring

**Install:**
```bash
# macOS (Homebrew)
brew install --cask visualvm

# Or download from:
# https://visualvm.github.io/
```

**Usage:**
1. Start VisualVM: `visualvm`
2. Run your demo: `./gradlew :month-01-java-foundations:run -PmainClass=com.deepdive.month01.week01.HeapMemoryAnalysis`
3. The JVM process appears in VisualVM's left panel — click to attach
4. Go to **Monitor** tab to see heap, GC, threads live

---

### 3. JITWatch
> Used with: `JitCompilationDemo` — visualize JIT compilation decisions, inlining, and OSR

**Install:**
```bash
# Clone and build
git clone https://github.com/AdoptOpenJDK/jitwatch.git
cd jitwatch
mvn clean package -DskipTests
```

**Usage:**
```bash
# Run demo with JIT logging enabled
java -XX:+PrintCompilation \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:+LogCompilation \
     -XX:LogFile=jit.log \
     -cp build/classes/java/main \
     com.deepdive.month01.week02.JitCompilationDemo

# Open jit.log in JITWatch UI
java -jar jitwatch/target/jitwatch-jar-with-dependencies.jar
```

---

### 4. JVM Flags Reference
> Useful flags to experiment with during Week 1 and Week 2

```bash
# GC selection and logging
-XX:+UseG1GC                        # Use G1 garbage collector (default Java 9+)
-XX:+UseZGC                         # Use ZGC (low-latency, Java 15+)
-XX:+UseShenandoahGC                # Use Shenandoah GC
-Xms256m -Xmx512m                   # Initial and max heap size
-verbose:gc                         # Print GC events to stdout
-Xlog:gc*:file=gc.log               # Write GC log to file (Java 9+)

# JIT and compilation
-XX:+PrintCompilation               # Print each method as it is JIT compiled
-XX:+UnlockDiagnosticVMOptions      # Enable diagnostic flags
-XX:+PrintInlining                  # Show inlining decisions
-XX:CompileThreshold=100            # Lower JIT threshold for faster warmup in demos

# Virtual threads (Week 3)
-Djdk.tracePinnedThreads=full       # Log when virtual threads are pinned to carrier
```

---

### 5. async-profiler (optional — advanced)
> CPU and allocation profiling at native level, no safepoint bias

**Install:**
```bash
# macOS
brew install async-profiler

# Or download from:
# https://github.com/async-profiler/async-profiler/releases
```

**Usage:**
```bash
# Profile CPU for 30 seconds and generate flamegraph
asprof -d 30 -f flamegraph.html <PID>
# Then open flamegraph.html in a browser
```

---

## Quick Start

```bash
# Compile the module
./gradlew :month-01-java-foundations:compileJava

# Run Week 1 — GC demo with G1 flags
./gradlew :month-01-java-foundations:run \
  -PmainClass=com.deepdive.month01.week01.GarbageCollectionDemo \
  --jvmArgs="-Xms128m -Xmx256m -XX:+UseG1GC -verbose:gc"

# Run Week 3 — Virtual threads demo
./gradlew :month-01-java-foundations:run \
  -PmainClass=com.deepdive.month01.week03.ThreadPoolDemo \
  --jvmArgs="-Djdk.tracePinnedThreads=full"
```
