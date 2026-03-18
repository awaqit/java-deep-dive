package com.deepdive.month03.week10;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Week 10: Raft Consensus Algorithm
 *
 * CONCEPT: Raft is a consensus algorithm designed to be understandable.
 * It's used to elect a leader and replicate a log across a cluster.
 * Understanding Raft is essential for understanding etcd, CockroachDB, TiKV,
 * and any system built on distributed consensus.
 *
 * Core problem: How do N nodes agree on a value even if some nodes crash?
 * Answer: Get a majority (quorum) to agree.
 *
 * Raft uses:
 * - Leader election: One leader accepts all writes
 * - Log replication: Leader replicates log entries to followers
 * - Safety: Only committed (majority-replicated) entries are durable
 *
 * Node states:
 * - FOLLOWER:   Passive. Votes in elections. Receives log from leader.
 * - CANDIDATE:  Actively requesting votes from peers.
 * - LEADER:     Accepts writes. Replicates to followers. Sends heartbeats.
 *
 * Terms: Logical clock for elections. New election = new term.
 *
 * Election process:
 * 1. Follower times out waiting for heartbeat
 * 2. Follower becomes Candidate, increments term, votes for itself
 * 3. Candidate sends RequestVote to all peers
 * 4. Peers vote if: (a) haven't voted this term, (b) candidate's log is at least as up-to-date
 * 5. If majority votes received -> become Leader
 * 6. Leader sends AppendEntries heartbeats to prevent new elections
 *
 * Log replication:
 * 1. Client sends write to Leader
 * 2. Leader appends to local log
 * 3. Leader sends AppendEntries to all followers
 * 4. Once majority acknowledge, entry is COMMITTED
 * 5. Leader applies to state machine, responds to client
 */
public class RaftConsensusDemo {

    enum NodeState { FOLLOWER, CANDIDATE, LEADER }

    record LogEntry(int term, int index, String command) {}

    record VoteRequest(String candidateId, int term, int lastLogIndex, int lastLogTerm) {}
    record VoteResponse(String voterId, int term, boolean granted) {}

    record AppendEntriesRequest(String leaderId, int term, List<LogEntry> entries,
                                int commitIndex, int prevLogIndex, int prevLogTerm) {}
    record AppendEntriesResponse(String followerId, int term, boolean success, int matchIndex) {}

    /**
     * Simplified Raft node implementation.
     * Full Raft has many edge cases (this is educational, not production-ready).
     */
    static class RaftNode {
        final String nodeId;
        volatile NodeState state = NodeState.FOLLOWER;
        volatile int currentTerm = 0;
        volatile String votedFor = null;
        final List<LogEntry> log = new CopyOnWriteArrayList<>();
        volatile int commitIndex = 0;
        volatile int lastApplied = 0;
        volatile String currentLeaderId = null;

        // Leader-only state
        final Map<String, Integer> nextIndex = new ConcurrentHashMap<>();
        final Map<String, Integer> matchIndex = new ConcurrentHashMap<>();

        // Election timeout simulation
        volatile long lastHeartbeatTime = System.currentTimeMillis();
        final Random random = new Random();
        final long electionTimeoutMs; // Random: 150-300ms in real Raft

        private final List<RaftNode> cluster = new ArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);

        final List<String> appliedCommands = new CopyOnWriteArrayList<>();

        RaftNode(String nodeId) {
            this.nodeId = nodeId;
            this.electionTimeoutMs = 150 + random.nextInt(150); // 150-300ms
        }

        void joinCluster(List<RaftNode> peers) {
            cluster.addAll(peers);
        }

        // ======================== LEADER ELECTION ========================

        /**
         * CONCEPT: When a follower doesn't hear from leader within electionTimeout,
         * it starts an election to become the new leader.
         */
        void startElection() {
            currentTerm++;
            state = NodeState.CANDIDATE;
            votedFor = nodeId;
            System.out.printf("  [%s] Starting election! Term %d%n", nodeId, currentTerm);

            AtomicInteger votesReceived = new AtomicInteger(1); // Vote for self

            int lastLogIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index();
            int lastLogTerm = log.isEmpty() ? 0 : log.get(log.size() - 1).term();

            VoteRequest request = new VoteRequest(nodeId, currentTerm, lastLogIndex, lastLogTerm);

            for (RaftNode peer : cluster) {
                if (!peer.nodeId.equals(nodeId)) {
                    VoteResponse response = peer.handleVoteRequest(request);
                    if (response.granted()) {
                        int votes = votesReceived.incrementAndGet();
                        System.out.printf("  [%s] Got vote from %s. Total votes: %d%n",
                                nodeId, response.voterId(), votes);

                        // Check if we have majority
                        int quorum = (cluster.size() + 1) / 2 + 1; // +1 for self
                        if (votes >= quorum && state == NodeState.CANDIDATE) {
                            becomeLeader();
                        }
                    } else if (response.term() > currentTerm) {
                        // Higher term found - revert to follower
                        currentTerm = response.term();
                        state = NodeState.FOLLOWER;
                        votedFor = null;
                        return;
                    }
                }
            }
        }

        VoteResponse handleVoteRequest(VoteRequest request) {
            if (request.term() < currentTerm) {
                return new VoteResponse(nodeId, currentTerm, false);
            }
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                state = NodeState.FOLLOWER;
                votedFor = null;
            }

            // Vote if we haven't voted, and candidate's log is at least as up-to-date
            int myLastLogIndex = log.isEmpty() ? 0 : log.get(log.size() - 1).index();
            int myLastLogTerm = log.isEmpty() ? 0 : log.get(log.size() - 1).term();
            boolean logUpToDate = request.lastLogTerm() > myLastLogTerm ||
                    (request.lastLogTerm() == myLastLogTerm && request.lastLogIndex() >= myLastLogIndex);

            if ((votedFor == null || votedFor.equals(request.candidateId())) && logUpToDate) {
                votedFor = request.candidateId();
                lastHeartbeatTime = System.currentTimeMillis();
                System.out.printf("  [%s] Voted for %s in term %d%n", nodeId, request.candidateId(), currentTerm);
                return new VoteResponse(nodeId, currentTerm, true);
            }
            return new VoteResponse(nodeId, currentTerm, false);
        }

        void becomeLeader() {
            state = NodeState.LEADER;
            currentLeaderId = nodeId;
            System.out.printf("  [%s] *** BECAME LEADER for term %d ***%n", nodeId, currentTerm);

            // Initialize nextIndex for all followers
            int nextIdx = log.size() + 1;
            for (RaftNode peer : cluster) {
                nextIndex.put(peer.nodeId, nextIdx);
                matchIndex.put(peer.nodeId, 0);
            }

            // Send immediate heartbeat
            sendHeartbeats();
        }

        // ======================== LOG REPLICATION ========================

        /**
         * CONCEPT: Client sends command to leader.
         * Leader appends to log and replicates to followers.
         * Once majority acknowledge, command is committed.
         */
        boolean submitCommand(String command) {
            if (state != NodeState.LEADER) {
                System.out.printf("  [%s] Not leader! Redirect to %s%n", nodeId, currentLeaderId);
                return false;
            }

            int newIndex = log.size() + 1;
            LogEntry entry = new LogEntry(currentTerm, newIndex, command);
            log.add(entry);
            System.out.printf("  [%s-LEADER] Appended to log[%d]: %s%n", nodeId, newIndex, command);

            // Replicate to followers
            AtomicInteger acks = new AtomicInteger(1); // Leader itself
            for (RaftNode peer : cluster) {
                if (!peer.nodeId.equals(nodeId)) {
                    AppendEntriesResponse resp = peer.handleAppendEntries(
                            new AppendEntriesRequest(nodeId, currentTerm,
                                    List.of(entry), commitIndex,
                                    newIndex - 1, newIndex > 1 ? log.get(newIndex - 2).term() : 0));
                    if (resp.success()) {
                        acks.incrementAndGet();
                        matchIndex.put(peer.nodeId, resp.matchIndex());
                    }
                }
            }

            // Check if majority acknowledged -> commit
            int quorum = (cluster.size() + 1) / 2 + 1;
            if (acks.get() >= quorum) {
                commitIndex = newIndex;
                applyCommittedEntries();
                System.out.printf("  [%s-LEADER] COMMITTED %s (acks=%d, quorum=%d)%n",
                        nodeId, command, acks.get(), quorum);
                return true;
            } else {
                System.out.printf("  [%s-LEADER] Not enough acks (%d/%d) - not committed%n",
                        nodeId, acks.get(), quorum);
                return false;
            }
        }

        AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
            lastHeartbeatTime = System.currentTimeMillis();

            if (request.term() < currentTerm) {
                return new AppendEntriesResponse(nodeId, currentTerm, false, 0);
            }

            if (request.term() > currentTerm) {
                currentTerm = request.term();
                state = NodeState.FOLLOWER;
                votedFor = null;
            }

            currentLeaderId = request.leaderId();

            // Heartbeat (no entries) or log replication
            for (LogEntry entry : request.entries()) {
                // Add entry if not already present
                if (log.size() < entry.index()) {
                    log.add(entry);
                    System.out.printf("  [%s-FOLLOWER] Replicated log[%d]: %s%n",
                            nodeId, entry.index(), entry.command());
                }
            }

            // Apply committed entries
            if (request.commitIndex() > commitIndex) {
                commitIndex = Math.min(request.commitIndex(), log.size());
                applyCommittedEntries();
            }

            int matchIdx = log.isEmpty() ? 0 : log.get(log.size() - 1).index();
            return new AppendEntriesResponse(nodeId, currentTerm, true, matchIdx);
        }

        void sendHeartbeats() {
            if (state != NodeState.LEADER) return;
            for (RaftNode peer : cluster) {
                if (!peer.nodeId.equals(nodeId)) {
                    peer.handleAppendEntries(new AppendEntriesRequest(
                            nodeId, currentTerm, Collections.emptyList(),
                            commitIndex, log.size(), log.isEmpty() ? 0 : log.get(log.size() - 1).term()));
                }
            }
        }

        private void applyCommittedEntries() {
            while (lastApplied < commitIndex && lastApplied < log.size()) {
                LogEntry entry = log.get(lastApplied);
                appliedCommands.add(entry.command());
                lastApplied++;
                System.out.printf("  [%s] Applied to state machine: %s%n", nodeId, entry.command());
            }
        }

        void printStatus() {
            System.out.printf("  Node %s: state=%-9s term=%d log=%d committed=%d applied=%s%n",
                    nodeId, state, currentTerm, log.size(), commitIndex, appliedCommands);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Raft Consensus Demo ===");

        demonstrateLeaderElection();
        demonstrateLogReplication();
        demonstrateLeaderFailover();
    }

    private static List<RaftNode> createCluster(String... nodeIds) {
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : nodeIds) nodes.add(new RaftNode(id));
        for (RaftNode node : nodes) {
            List<RaftNode> peers = new ArrayList<>(nodes);
            node.joinCluster(peers);
        }
        return nodes;
    }

    private static void demonstrateLeaderElection() throws InterruptedException {
        System.out.println("\n--- Leader Election ---");
        List<RaftNode> cluster = createCluster("node-1", "node-2", "node-3");

        System.out.println("Initial state (all followers):");
        cluster.forEach(RaftNode::printStatus);

        // Simulate node-1 timing out and starting election
        System.out.println("\nNode-1 election timeout! Starting election...");
        cluster.get(0).startElection();

        System.out.println("\nAfter election:");
        cluster.forEach(RaftNode::printStatus);
    }

    private static void demonstrateLogReplication() throws InterruptedException {
        System.out.println("\n--- Log Replication ---");
        List<RaftNode> cluster = createCluster("node-A", "node-B", "node-C");

        // Elect node-A as leader
        cluster.get(0).startElection();
        RaftNode leader = cluster.stream().filter(n -> n.state == NodeState.LEADER).findFirst().orElseThrow();

        System.out.println("\nSubmitting commands to leader " + leader.nodeId + ":");
        leader.submitCommand("SET x=1");
        leader.submitCommand("SET y=2");
        leader.submitCommand("INCREMENT x");

        System.out.println("\nFinal state of all nodes:");
        cluster.forEach(RaftNode::printStatus);
        System.out.println("\nAll nodes have same applied commands: " +
                cluster.stream().allMatch(n -> n.appliedCommands.equals(leader.appliedCommands)));
    }

    private static void demonstrateLeaderFailover() throws InterruptedException {
        System.out.println("\n--- Leader Failover ---");
        List<RaftNode> cluster = createCluster("alpha", "beta", "gamma");

        // Elect initial leader
        cluster.get(0).startElection();
        RaftNode oldLeader = cluster.stream().filter(n -> n.state == NodeState.LEADER).findFirst().orElseThrow();
        System.out.println("Initial leader: " + oldLeader.nodeId);
        oldLeader.submitCommand("SET db=main");

        // Simulate leader failure (remove from heartbeat)
        System.out.println("\n*** Leader " + oldLeader.nodeId + " FAILED! ***");
        oldLeader.state = NodeState.FOLLOWER; // Force it to stop being leader

        // Another node detects timeout and starts election
        RaftNode newCandidate = cluster.stream()
                .filter(n -> n != oldLeader)
                .findFirst()
                .orElseThrow();
        Thread.sleep(100);
        newCandidate.startElection();

        RaftNode newLeader = cluster.stream()
                .filter(n -> n.state == NodeState.LEADER)
                .findFirst()
                .orElse(null);

        if (newLeader != null) {
            System.out.println("New leader elected: " + newLeader.nodeId + " (term=" + newLeader.currentTerm + ")");
            newLeader.submitCommand("SET backup=true");
        }

        System.out.println("\nFinal cluster state:");
        cluster.forEach(RaftNode::printStatus);
        System.out.println("\nRaft guarantees: new leader has all committed entries from previous term.");
    }
}
