package tools.vitruv.framework.vsum.branch.merge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;

class CommitDependencyGraphTest {

    @Test
    void noEdges_topologicalSortReturnsAllNodes() {
        CommitDependencyGraph graph = new CommitDependencyGraph(2, 2);
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertEquals(4, ordering.size());
        // Intra-branch ordering: a0 before a1, b0 before b1
        // No inter-branch constraints → any valid topo order is acceptable
        assertFalse(graph.hasCycle());
    }

    @Test
    void intraBranchOrderingPreserved() {
        // 3 A-commits, 0 B-commits
        CommitDependencyGraph graph = new CommitDependencyGraph(3, 0);
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertEquals(3, ordering.size());
        // All must be A
        assertTrue(ordering.stream().allMatch(b -> b));
    }

    @Test
    void singleInterBranchEdge_ABeforeB() {
        // 1 A-commit, 1 B-commit; add edge A0 → B0 (A must come before B)
        CommitDependencyGraph graph = new CommitDependencyGraph(1, 1);
        graph.addEdge(graph.nodeA(0), graph.nodeB(0));
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertEquals(2, ordering.size());
        // A must come first
        assertTrue(ordering.get(0)); // first = A
        assertFalse(ordering.get(1)); // second = B
    }

    @Test
    void singleInterBranchEdge_BBeforeA() {
        CommitDependencyGraph graph = new CommitDependencyGraph(1, 1);
        graph.addEdge(graph.nodeB(0), graph.nodeA(0));
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertEquals(2, ordering.size());
        assertFalse(ordering.get(0)); // first = B
        assertTrue(ordering.get(1));  // second = A
    }

    @Test
    void cycle_hasCycleReturnsTrueAndSortReturnsNull() {
        CommitDependencyGraph graph = new CommitDependencyGraph(1, 1);
        // A0 → B0 and B0 → A0 = cycle
        graph.addEdge(graph.nodeA(0), graph.nodeB(0));
        graph.addEdge(graph.nodeB(0), graph.nodeA(0));
        assertTrue(graph.hasCycle());
        assertNull(graph.topologicalSort());
    }

    @Test
    void cycle_getCyclicPairsReturnsCorrectPairs() {
        CommitDependencyGraph graph = new CommitDependencyGraph(2, 2);
        // Create cycle between A[0] and B[1]
        graph.addEdge(graph.nodeA(0), graph.nodeB(1));
        graph.addEdge(graph.nodeB(1), graph.nodeA(0));
        List<int[]> pairs = graph.getCyclicPairs();
        // Should find pair (0, 1) = a[0] and b[1]
        assertTrue(pairs.stream().anyMatch(p -> p[0] == 0 && p[1] == 1));
    }

    @Test
    void multipleCommitsWithConstraints_validOrdering() {
        // 2 A-commits, 2 B-commits; B[0] must come before A[1]
        CommitDependencyGraph graph = new CommitDependencyGraph(2, 2);
        graph.addEdge(graph.nodeB(0), graph.nodeA(1));
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertEquals(4, ordering.size());
        assertFalse(graph.hasCycle());
        // B[0] (index m+0=2) must appear before A[1] (index 1) in ordering
        int posA1 = -1, posB0 = -1;
        int aIdx = 0, bIdx = 0;
        for (int i = 0; i < ordering.size(); i++) {
            if (ordering.get(i)) {
                if (aIdx == 1) posA1 = i;
                aIdx++;
            } else {
                if (bIdx == 0) posB0 = i;
                bIdx++;
            }
        }
        assertTrue(posB0 < posA1, "B[0] should come before A[1]");
    }

    @Test
    void noCycle_idempotentEdgeAdd() {
        CommitDependencyGraph graph = new CommitDependencyGraph(1, 1);
        graph.addEdge(graph.nodeA(0), graph.nodeB(0));
        graph.addEdge(graph.nodeA(0), graph.nodeB(0)); // duplicate, should be ignored
        List<Boolean> ordering = graph.topologicalSort();
        assertNotNull(ordering);
        assertFalse(graph.hasCycle());
    }
}
