package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.BenchmarkQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BenchmarkQuestionRepository
        extends JpaRepository<BenchmarkQuestion, Long> {

    List<BenchmarkQuestion> findByConnectionId(Long connectionId);

    Optional<BenchmarkQuestion> findByIdAndConnectionId(Long id, Long connectionId);

    List<BenchmarkQuestion> findByConnectionIdAndLanguage(
            Long connectionId,
            String language
    );

    long countByConnectionId(Long connectionId);
}