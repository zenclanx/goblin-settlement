package dev.local.goblinsettlement.colony.family;

import dev.local.goblinsettlement.colony.ResidentRecord;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Excludes direct ancestors, siblings, and cousins through known grandparents. */
public final class KinshipRules {
    private KinshipRules() {
    }

    public static boolean areCloseKin(ResidentRecord first, ResidentRecord second,
                                      Map<String, ResidentRecord> residents) {
        Set<String> firstAncestors = ancestors(first, residents);
        Set<String> secondAncestors = ancestors(second, residents);
        firstAncestors.add(first.id());
        secondAncestors.add(second.id());
        firstAncestors.retainAll(secondAncestors);
        return !firstAncestors.isEmpty();
    }

    private static Set<String> ancestors(ResidentRecord resident, Map<String, ResidentRecord> residents) {
        Set<String> result = new HashSet<>();
        addParent(resident.motherId().orElse(null), residents, result, 2);
        addParent(resident.fatherId().orElse(null), residents, result, 2);
        return result;
    }

    private static void addParent(String parentId, Map<String, ResidentRecord> residents,
                                  Set<String> result, int generations) {
        if (parentId == null || generations == 0 || !result.add(parentId)) {
            return;
        }
        ResidentRecord parent = residents.get(parentId);
        if (parent != null) {
            addParent(parent.motherId().orElse(null), residents, result, generations - 1);
            addParent(parent.fatherId().orElse(null), residents, result, generations - 1);
        }
    }
}
