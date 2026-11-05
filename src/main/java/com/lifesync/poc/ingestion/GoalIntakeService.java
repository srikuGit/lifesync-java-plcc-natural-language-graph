package com.lifesync.poc.ingestion;

import com.lifesync.poc.classification.GoalClassificationService;
import com.lifesync.poc.domain.Models.Goal;
import com.lifesync.poc.graph.GraphRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Future external goal-event consumer can call this; no goal-creation UI or service is exposed by this POC. */
@Service
public class GoalIntakeService {
    private final GraphRepository graph;
    private final GoalClassificationService classifier;

    public GoalIntakeService(GraphRepository graph, GoalClassificationService classifier) {
        this.graph = graph;
        this.classifier = classifier;
    }

    public void ingestExistingOrNewGoal(Goal goal) {
        if (graph.customerByEcn(goal.ecn()).isEmpty()) {
            throw new IllegalArgumentException("Customer must be ingested before goal " + goal.goalId());
        }
        graph.upsertGoalsMulti(List.of(goal), Map.of(goal.goalId(), classifier.classifyAll(goal)));
    }
}
