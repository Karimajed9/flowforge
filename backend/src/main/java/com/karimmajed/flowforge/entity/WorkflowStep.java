package com.karimmajed.flowforge.entity;

import com.karimmajed.flowforge.enums.StepType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "workflow_steps")
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    private Workflow workflow;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    private StepType stepType;

    @Column(nullable = false)
    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration",
            columnDefinition = "jsonb",
            nullable = false)
    private Map<String, Object> configuration = new HashMap<>();

    protected WorkflowStep() {
    }

    public WorkflowStep(
            Workflow workflow,
            String name,
            StepType stepType,
            int position) {
        this.workflow = workflow;
        this.name = name;
        this.stepType = stepType;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public StepType getStepType() {
        return stepType;
    }

    public int getPosition() {
        return position;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }
}
