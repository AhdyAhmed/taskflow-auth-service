package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByProject_Id(Long projectId);

    List<Task> findByAssignee_Id(Long assigneeId);

    List<Task> findByProject_IdAndStatus(Long projectId, TaskStatus status);
}
