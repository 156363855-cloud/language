package com.example.lingualink.repository;

import com.example.lingualink.model.TaskFolder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFolderRepository extends JpaRepository<TaskFolder, String> {
}
