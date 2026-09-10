package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByProject_Id(Long projectId);

    /** Paginated variant used by the "list tasks in a project" endpoint. */
    Page<Task> findByProject_Id(Long projectId, Pageable pageable);

    List<Task> findByAssignee_Id(Long assigneeId);

    List<Task> findByProject_IdAndStatus(Long projectId, TaskStatus status);
}
