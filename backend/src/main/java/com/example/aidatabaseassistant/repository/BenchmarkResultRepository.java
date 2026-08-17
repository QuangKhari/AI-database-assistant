package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.BenchmarkResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkResultRepository extends JpaRepository<BenchmarkResult, Long> {
    List<BenchmarkResult> findByBenchmarkQuestionId(Long benchmarkQuestionId);
}