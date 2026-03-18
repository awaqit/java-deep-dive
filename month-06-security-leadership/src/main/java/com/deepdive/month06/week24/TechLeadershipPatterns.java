package com.deepdive.month06.week24;

import java.time.LocalDate;
import java.util.*;

/**
 * Week 24: Tech Leadership & Mentorship
 * Topics: Leading cross-functional teams, mentoring junior engineers,
 *         Architecture Decision Records (ADR), code review culture
 *
 * CONCEPT: Staff Engineers don't just write code — they multiply the
 * effectiveness of entire teams through technical leadership.
 *
 * Key responsibilities at Staff+ level:
 * - Define technical direction across multiple teams
 * - Write Architecture Decision Records (ADRs)
 * - Build a code review culture that educates, not just gatekeeps
 * - Mentor engineers toward independent problem-solving
 * - Identify and address systemic technical risks
 */
public class TechLeadershipPatterns {

    public static void main(String[] args) {
        System.out.println("=== Tech Leadership Patterns ===\n");

        // Demonstrate ADR creation
        ArchitectureDecisionRecord adr = new ArchitectureDecisionRecord(
                "ADR-001",
                "Use Kafka for async inter-service communication",
                LocalDate.now()
        );
        adr.addContext("Services need to communicate without tight coupling. " +
                "Synchronous REST calls create cascading failures.");
        adr.addOption("Synchronous REST", "Simple, well-known", "Tight coupling, cascading failures");
        adr.addOption("RabbitMQ", "Simple AMQP, good for task queues", "Limited replay, no log compaction");
        adr.addOption("Apache Kafka", "Durable log, replay, high throughput", "Operational complexity");
        adr.decide("Apache Kafka", "Kafka's durable log enables event replay and decoupled consumers. " +
                "Operational complexity is acceptable given our SRE team's Kafka expertise.");
        System.out.println(adr.toMarkdown());

        // Demonstrate structured code review feedback
        System.out.println("\n=== Code Review Feedback Framework ===");
        CodeReviewFeedback review = new CodeReviewFeedback("PR #342 - Add retry logic to PaymentService");
        review.addComment(CodeReviewFeedback.Type.BLOCKING,
                "PaymentService.java:87",
                "This retry loop has no backoff — it will hammer the downstream service under failure. " +
                "Use exponential backoff with jitter (see RetryPatternDemo in month-03/week12).");
        review.addComment(CodeReviewFeedback.Type.SUGGESTION,
                "PaymentService.java:102",
                "Consider extracting the retry logic into a shared utility — we have similar patterns in 3 services now.");
        review.addComment(CodeReviewFeedback.Type.PRAISE,
                "PaymentService.java:55",
                "Great use of the circuit breaker here — exactly the pattern we want to standardize.");
        review.addComment(CodeReviewFeedback.Type.EDUCATIONAL,
                "PaymentService.java:120",
                "WHY: Thread.sleep() in a reactive pipeline blocks the scheduler thread. " +
                "Use Mono.delay() instead to stay non-blocking.");
        System.out.println(review.toMarkdown());

        // Demonstrate mentorship framework
        System.out.println("\n=== Mentorship Growth Framework ===");
        MentorshipPlan plan = new MentorshipPlan("Junior Engineer", "Distributed systems fundamentals");
        plan.addMilestone("Week 1-2", "Read and summarize CAP theorem. Implement CapTheoremDemo.");
        plan.addMilestone("Week 3-4", "Add a new consistency model to EventualConsistencyDemo.");
        plan.addMilestone("Month 2", "Lead design review for one feature. Staff engineer observes only.");
        plan.addMilestone("Month 3", "Own a cross-team RFC. Staff engineer is a reviewer, not author.");
        System.out.println(plan.toMarkdown());

        // Technical strategy: Identifying systemic risks
        System.out.println("\n=== Systemic Risk Radar ===");
        RiskRadar radar = new RiskRadar();
        radar.addRisk("Single point of failure in payment DB", RiskRadar.Severity.HIGH,
                "Migrate to multi-region active-active by Q3");
        radar.addRisk("No circuit breakers between order and inventory service", RiskRadar.Severity.HIGH,
                "Add Resilience4j circuit breakers — see month-03/week12");
        radar.addRisk("Manual deployment process for 12 services", RiskRadar.Severity.MEDIUM,
                "GitOps with ArgoCD — tracked in ADR-003");
        radar.addRisk("No SLOs defined for internal APIs", RiskRadar.Severity.MEDIUM,
                "Define SLOs, instrument with Micrometer, alert via Grafana");
        System.out.println(radar.toMarkdown());
    }

    // ─────────────────────────────────────────────────────────────────
    // Architecture Decision Record (ADR)
    // WHY: ADRs capture the WHY behind technical decisions, so future
    // engineers understand context — not just what was decided.
    // ─────────────────────────────────────────────────────────────────
    record Option(String name, String pros, String cons) {}

    static class ArchitectureDecisionRecord {
        private final String id;
        private final String title;
        private final LocalDate date;
        private String context = "";
        private final List<Option> options = new ArrayList<>();
        private String decision = "";
        private String rationale = "";
        private String status = "PROPOSED";

        ArchitectureDecisionRecord(String id, String title, LocalDate date) {
            this.id = id;
            this.title = title;
            this.date = date;
        }

        void addContext(String context) { this.context = context; }

        void addOption(String name, String pros, String cons) {
            options.add(new Option(name, pros, cons));
        }

        void decide(String decision, String rationale) {
            this.decision = decision;
            this.rationale = rationale;
            this.status = "ACCEPTED";
        }

        String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(id).append(": ").append(title).append("\n");
            sb.append("**Date:** ").append(date).append(" | **Status:** ").append(status).append("\n\n");
            sb.append("## Context\n").append(context).append("\n\n");
            sb.append("## Options Considered\n");
            for (Option o : options) {
                sb.append("- **").append(o.name()).append("**: Pros=").append(o.pros())
                  .append(", Cons=").append(o.cons()).append("\n");
            }
            sb.append("\n## Decision\n**").append(decision).append("**\n\n");
            sb.append("## Rationale\n").append(rationale).append("\n");
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Code Review Feedback Framework
    // WHY: Good code reviews educate, not just gatekeep. Categorizing
    // feedback (blocking vs suggestion vs educational) reduces friction
    // and builds team capability.
    // ─────────────────────────────────────────────────────────────────
    static class CodeReviewFeedback {
        enum Type { BLOCKING, SUGGESTION, PRAISE, EDUCATIONAL }

        record Comment(Type type, String location, String message) {}

        private final String prTitle;
        private final List<Comment> comments = new ArrayList<>();

        CodeReviewFeedback(String prTitle) { this.prTitle = prTitle; }

        void addComment(Type type, String location, String message) {
            comments.add(new Comment(type, location, message));
        }

        String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Code Review: ").append(prTitle).append("\n");
            for (Type type : Type.values()) {
                List<Comment> filtered = comments.stream()
                        .filter(c -> c.type() == type).toList();
                if (!filtered.isEmpty()) {
                    sb.append("\n### ").append(type).append("\n");
                    for (Comment c : filtered) {
                        sb.append("- `").append(c.location()).append("`: ").append(c.message()).append("\n");
                    }
                }
            }
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Mentorship Growth Framework
    // WHY: Great mentors give engineers progressively harder challenges
    // with decreasing hand-holding — building independence, not dependency.
    // ─────────────────────────────────────────────────────────────────
    record Milestone(String timeframe, String goal) {}

    static class MentorshipPlan {
        private final String mentee;
        private final String focus;
        private final List<Milestone> milestones = new ArrayList<>();

        MentorshipPlan(String mentee, String focus) {
            this.mentee = mentee;
            this.focus = focus;
        }

        void addMilestone(String timeframe, String goal) {
            milestones.add(new Milestone(timeframe, goal));
        }

        String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Mentorship Plan: ").append(mentee).append(" — ").append(focus).append("\n");
            for (Milestone m : milestones) {
                sb.append("- **").append(m.timeframe()).append("**: ").append(m.goal()).append("\n");
            }
            return sb.toString();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Systemic Risk Radar
    // WHY: Staff Engineers proactively identify risks before they become
    // incidents. A risk radar ensures nothing falls through the cracks.
    // ─────────────────────────────────────────────────────────────────
    record Risk(String description, RiskRadar.Severity severity, String mitigation) {}

    static class RiskRadar {
        enum Severity { HIGH, MEDIUM, LOW }

        private final List<Risk> risks = new ArrayList<>();

        void addRisk(String description, Severity severity, String mitigation) {
            risks.add(new Risk(description, severity, mitigation));
        }

        String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## Systemic Risk Radar\n");
            for (Severity s : Severity.values()) {
                List<Risk> filtered = risks.stream().filter(r -> r.severity() == s).toList();
                if (!filtered.isEmpty()) {
                    sb.append("\n### ").append(s).append("\n");
                    for (Risk r : filtered) {
                        sb.append("- **").append(r.description()).append("**\n");
                        sb.append("  → Mitigation: ").append(r.mitigation()).append("\n");
                    }
                }
            }
            return sb.toString();
        }
    }
}
