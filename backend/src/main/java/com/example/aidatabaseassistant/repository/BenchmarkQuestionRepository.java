package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.BenchmarkQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenchmarkQuestionRepository
        extends JpaRepository<BenchmarkQuestion, Long> {

    List<BenchmarkQuestion> findByConnectionId(Long connectionId);

    List<BenchmarkQuestion> findByConnectionIdAndLanguage(
            Long connectionId,
            String language
    );
}