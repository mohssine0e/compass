package com.compass.app.entry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DataIntegrityService {
    private final EntryRepository repository;

    public DataIntegrityService(EntryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Report inspect(boolean apply) {
        List<Entry> all = repository.findAll();
        Map<Long, Entry> byId = new HashMap<>();
        all.forEach(entry -> byId.put(entry.getId(), entry));
        List<String> issues = new ArrayList<>();
        Set<Entry> changed = new HashSet<>();

        for (Entry entry : all) {
            if (entry.getParentId() != null && !byId.containsKey(entry.getParentId())) {
                issues.add("entry " + entry.getId() + " has a missing parent");
                if (apply) {
                    entry.setParentId(null);
                    entry.setOrderIndex(null);
                    changed.add(entry);
                }
            }
            if (entry.getDependsOn() != null && !byId.containsKey(entry.getDependsOn())) {
                issues.add("entry " + entry.getId() + " has a missing prerequisite");
                if (apply) {
                    entry.setDependsOn(null);
                    changed.add(entry);
                }
            }
            if (entry.getContent() == null) {
                issues.add("entry " + entry.getId() + " has null content");
                if (apply) {
                    entry.setContent(new HashMap<>());
                    changed.add(entry);
                }
            }
        }

        Map<Long, List<Entry>> siblings = new HashMap<>();
        all.stream().filter(e -> e.getParentId() != null)
                .forEach(e -> siblings.computeIfAbsent(e.getParentId(), ignored -> new ArrayList<>()).add(e));
        for (List<Entry> group : siblings.values()) {
            group.sort(Comparator.comparing(e -> e.getOrderIndex() == null ? Integer.MAX_VALUE : e.getOrderIndex()));
            for (int i = 0; i < group.size(); i++) {
                if (!Integer.valueOf(i).equals(group.get(i).getOrderIndex())) {
                    issues.add("entry " + group.get(i).getId() + " has an invalid order index");
                    if (apply) {
                        group.get(i).setOrderIndex(i);
                        changed.add(group.get(i));
                    }
                }
            }
        }
        if (apply && !changed.isEmpty()) repository.saveAll(changed);
        return new Report(issues.size(), apply ? changed.size() : 0, issues);
    }

    public record Report(int issueCount, int repairedCount, List<String> issues) {}
}
