package com.compass.app.roadmap;

import com.compass.app.entry.Entry;
import com.compass.app.entry.EntryRepository;
import com.compass.app.entry.EntryStatus;
import com.compass.app.entry.EntryType;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class RoadmapDependencyService {

    private final EntryRepository repository;

    public RoadmapDependencyService(EntryRepository repository) {
        this.repository = repository;
    }

    public Entry validate(Entry step, Long prerequisiteId) {
        if (step.getType() != EntryType.ROADMAP_STEP) {
            throw new IllegalArgumentException("Only roadmap steps can have prerequisites.");
        }
        if (step.getId().equals(prerequisiteId)) {
            throw new IllegalArgumentException("A step can't be its own prerequisite.");
        }

        Entry prerequisite = repository.findById(prerequisiteId)
                .filter(entry -> entry.getType() == EntryType.ROADMAP_STEP)
                .orElseThrow(() -> new NoSuchElementException("No roadmap step with id " + prerequisiteId));

        if (!rootId(step).equals(rootId(prerequisite))) {
            throw new IllegalArgumentException("A prerequisite must belong to the same roadmap.");
        }

        Set<Long> visited = new HashSet<>();
        Entry current = prerequisite;
        while (current.getDependsOn() != null) {
            if (!visited.add(current.getId())) {
                throw new IllegalArgumentException("The existing prerequisite chain contains a cycle.");
            }
            if (current.getDependsOn().equals(step.getId())) {
                throw new IllegalArgumentException("This prerequisite would create a cycle.");
            }
            current = repository.findById(current.getDependsOn())
                    .filter(entry -> entry.getType() == EntryType.ROADMAP_STEP)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The prerequisite chain points to a missing step."));
        }
        return prerequisite;
    }

    public void requireDone(Entry step) {
        if (step.getDependsOn() == null) return;
        Entry prerequisite = validate(step, step.getDependsOn());
        if (prerequisite.getStatus() != EntryStatus.DONE) {
            throw new IllegalArgumentException("Finish the prerequisite before marking this step done.");
        }
    }

    private Long rootId(Entry entry) {
        var ancestors = repository.findAncestors(entry.getId());
        if (ancestors.isEmpty()) {
            throw new IllegalArgumentException("The step is not attached to a roadmap.");
        }
        Entry root = ancestors.get(ancestors.size() - 1);
        if (root.getType() != EntryType.ROADMAP || root.getParentId() != null) {
            throw new IllegalArgumentException("The step has an invalid roadmap parent chain.");
        }
        return root.getId();
    }
}
