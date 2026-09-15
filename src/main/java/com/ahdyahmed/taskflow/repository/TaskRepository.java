package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByProject_Id(Long projectId);

    /** Paginated variant used by the "list tasks in a project" endpoint. */
    Page<Task> findByProject_Id(Long projectId, Pageable pageable);

    List<Task> findByAssignee_Id(Long assigneeId);

    List<Task> findByProject_IdAndStatus(Long projectId, TaskStatus status);

    /**
     * Day 8: backs {@code GET /api/tasks} for non-ADMIN callers. "Accessible"
     * is broader than project membership alone — a task assigned to you is
     * yours to see even in a project you're not otherwise a member of
     * (e.g. a manager assigned it to you directly). {@code distinct}
     * avoids double-counting a task where the caller is both a project
     * member and the assignee.
     */
    @Query("""
            select distinct t from Task t
            left join t.project p
            left join p.members m
            where p.owner.id = :userId or m.id = :userId or t.assignee.id = :userId
            """)
    Page<Task> findAccessibleTo(@Param("userId") Long userId, Pageable pageable);
}
