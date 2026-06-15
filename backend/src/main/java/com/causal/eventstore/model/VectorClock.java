package com.causal.eventstore.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VectorClock {
    private List<Integer> clocks;

    public VectorClock(int dimensions) {
        this.clocks = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            this.clocks.add(0);
        }
    }

    public VectorClock increment(int partitionId) {
        List<Integer> newClocks = new ArrayList<>(this.clocks);
        newClocks.set(partitionId, newClocks.get(partitionId) + 1);
        return new VectorClock(newClocks);
    }

    public VectorClock merge(VectorClock other) {
        if (other == null) return new VectorClock(new ArrayList<>(this.clocks));
        int size = Math.max(this.clocks.size(), other.clocks.size());
        List<Integer> merged = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int a = i < this.clocks.size() ? this.clocks.get(i) : 0;
            int b = i < other.clocks.size() ? other.clocks.get(i) : 0;
            merged.add(Math.max(a, b));
        }
        return new VectorClock(merged);
    }

    public static VectorClock merge(List<VectorClock> clocks) {
        if (clocks == null || clocks.isEmpty()) {
            return null;
        }
        VectorClock result = clocks.get(0);
        for (int i = 1; i < clocks.size(); i++) {
            result = result.merge(clocks.get(i));
        }
        return result;
    }

    public int compareTo(VectorClock other) {
        boolean allLessOrEqual = true;
        boolean allGreaterOrEqual = true;
        boolean hasStrictLess = false;
        boolean hasStrictGreater = false;

        int size = Math.max(this.clocks.size(), other.clocks.size());
        for (int i = 0; i < size; i++) {
            int a = i < this.clocks.size() ? this.clocks.get(i) : 0;
            int b = i < other.clocks.size() ? other.clocks.get(i) : 0;
            if (a < b) {
                hasStrictLess = true;
                allGreaterOrEqual = false;
            } else if (a > b) {
                hasStrictGreater = true;
                allLessOrEqual = false;
            }
        }

        if (allLessOrEqual && hasStrictLess) return -1;
        if (allGreaterOrEqual && hasStrictGreater) return 1;
        if (!allLessOrEqual && !allGreaterOrEqual) return 2;
        return 0;
    }

    public boolean isConcurrent(VectorClock other) {
        return compareTo(other) == 2;
    }

    public boolean happensBefore(VectorClock other) {
        return compareTo(other) == -1;
    }

    public boolean strictlyGreaterThan(VectorClock other) {
        return compareTo(other) == 1;
    }

    public int getPartition(int partitionId) {
        if (partitionId < 0 || partitionId >= clocks.size()) {
            return 0;
        }
        return clocks.get(partitionId);
    }

    public int[] toIntArray() {
        return clocks.stream().mapToInt(Integer::intValue).toArray();
    }

    public static VectorClock fromIntArray(int[] arr) {
        if (arr == null) return null;
        List<Integer> list = new ArrayList<>(arr.length);
        for (int v : arr) list.add(v);
        return new VectorClock(list);
    }

    @Override
    public VectorClock clone() {
        return new VectorClock(new ArrayList<>(this.clocks));
    }
}
