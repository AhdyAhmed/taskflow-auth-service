package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwner_Id(Long ownerId);

    List<Project> findByMembers_Id(Long userId);

    /**
     * Day 8: backs {@code GET /api/projects} for non-ADMIN callers — "list
     * projects" means "list projects I have access to", not every project
     * in the system. {@code distinct} matters here: without it, an owner
     * who's also (redundantly) listed as a member would come back twice.
     */
    @Query("""
            select distinct p from Project p
            left join p.members m
            where p.owner.id = :userId or m.id = :userId
            """)
    Page<Project> findAccessibleTo(@Param("userId") Long userId, Pageable pageable);
}
