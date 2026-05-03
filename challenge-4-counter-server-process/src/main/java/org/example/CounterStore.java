package org.example;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CounterStore {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public Counter get(String id) {
        return counters.get(id);
    }

    public boolean has(String id) {
        return counters.containsKey(id);
    }

    public void add(String id, Counter counter) {
        counters.put(id, counter);
    }

    public void remove(String id) {
        counters.remove(id);
    }

    public Set<Map.Entry<String, Counter>> entries() {
        return counters.entrySet();
    }

    public boolean isEmpty() {
        return counters.isEmpty();
    }
}
