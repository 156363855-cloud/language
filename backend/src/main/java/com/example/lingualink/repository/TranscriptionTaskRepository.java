package com.example.lingualink.repository;

import com.example.lingualink.model.TaskStatus;
import com.example.lingualink.model.TranscriptionTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TranscriptionTaskRepository extends JpaRepository<TranscriptionTask, String> {
    List<TranscriptionTask> findByStatusIn(Collection<TaskStatus> statuses);
}
