package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwner_Id(Long ownerId);

    List<Project> findByMembers_Id(Long userId);
}
