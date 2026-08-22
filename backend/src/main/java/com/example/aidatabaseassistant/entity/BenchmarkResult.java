package com.example.aidatabaseassistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "benchmark_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BenchmarkResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benchmark_question_id", nullable = false)
    private BenchmarkQuestion benchmarkQuestion;

    @Column(name = "generated_sql", columnDefinition = "TEXT")
    private String generatedSql;

    @Column(name = "expected_sql", columnDefinition = "TEXT")
    private String expectedSql;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect;

    @Column(name = "run_at", updatable = false)
    private LocalDateTime runAt;

    @PrePersist
    protected void onCreate() {
        this.runAt = LocalDateTime.now();
    }

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "model_used", length = 100)
    private String modelUsed;
}