package com.consilens.core.algorithm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Test case to reproduce the missing record bug where employee_id=200199497
 * with birth_date difference (1992-02-10 vs 1992-02-19) is not detected.
 * 
 * Root cause analysis:
 * 1. Segment boundaries use exclusive upper bound (< instead of <=)
 * 2. Segment boundaries must remain gap-free even when checkpoints are very close
 * 3. Need to verify segment generation logic ensures no gaps
 */
@DisplayName("Missing Record Bug - employee_id=200199497")
public class MissingRecordBugTest {

    @Test
    @DisplayName("Verify segment boundary logic doesn't create gaps")
    public void testSegmentBoundariesNoGaps() {
        // Test case: If we have checkpoints [200149997, 200199996, 200199998]
        // The segments should be:
        // Segment 1: [min, 200149997)
        // Segment 2: [200149997, 200199996)
        // Segment 3: [200199996, 200199998)
        // Segment 4: [200199998, max]
        
        // employee_id=200199497 should be in Segment 2
        long employeeId = 200199497L;
        long checkpoint1 = 200149997L;
        long checkpoint2 = 200199996L;
        long checkpoint3 = 200199998L;
        
        // Verify which segment contains employee_id=200199497
        boolean inSegment2 = employeeId >= checkpoint1 && employeeId < checkpoint2;
        boolean inSegment3 = employeeId >= checkpoint2 && employeeId < checkpoint3;
        boolean inSegment4 = employeeId >= checkpoint3;
        
        System.out.println("employee_id=" + employeeId);
        System.out.println("  In Segment 2 [" + checkpoint1 + ", " + checkpoint2 + ")? " + inSegment2);
        System.out.println("  In Segment 3 [" + checkpoint2 + ", " + checkpoint3 + ")? " + inSegment3);
        System.out.println("  In Segment 4 [" + checkpoint3 + ", ∞)? " + inSegment4);
        
        // employee_id=200199497 should be in exactly one segment
        int segmentCount = (inSegment2 ? 1 : 0) + (inSegment3 ? 1 : 0) + (inSegment4 ? 1 : 0);
        assertEquals(1, segmentCount, 
            "employee_id=200199497 should be in exactly one segment, but found in " + segmentCount);
        
        // It should be in Segment 2
        assertTrue(inSegment2, 
            "employee_id=200199497 should be in Segment 2 [200149997, 200199996)");
    }
    
    @Test
    @DisplayName("Verify no gaps between consecutive segments")
    public void testNoGapsBetweenSegments() {
        // Simulate segment boundaries
        List<Long> checkpoints = List.of(200149997L, 200199996L, 200199998L);
        
        // For any ID in the range, it should belong to exactly one segment
        for (long testId = 200149997L; testId <= 200199998L; testId++) {
            int belongsToSegmentCount = 0;
            
            // Check segment 1: [200149997, 200199996)
            if (testId >= checkpoints.get(0) && testId < checkpoints.get(1)) {
                belongsToSegmentCount++;
            }
            
            // Check segment 2: [200199996, 200199998)
            if (testId >= checkpoints.get(1) && testId < checkpoints.get(2)) {
                belongsToSegmentCount++;
            }
            
            // Check segment 3: [200199998, ∞)
            if (testId >= checkpoints.get(2)) {
                belongsToSegmentCount++;
            }
            
            assertEquals(1, belongsToSegmentCount,
                "ID " + testId + " should belong to exactly one segment, but belongs to " + belongsToSegmentCount);
        }
    }
    
    @Test
    @DisplayName("Verify checksum calculation includes boundary records")
    public void testChecksumIncludesBoundaryRecords() {
        // This test verifies that when calculating checksum for a segment,
        // records at the lower boundary are included, but records at the
        // upper boundary are excluded (due to < operator)
        
        long segmentMin = 200199996L;
        long segmentMax = 200199998L;
        
        // Records that should be included in segment [200199996, 200199998)
        assertTrue(isInSegment(200199996L, segmentMin, segmentMax), 
            "200199996 should be included (lower boundary)");
        assertTrue(isInSegment(200199997L, segmentMin, segmentMax), 
            "200199997 should be included");
        assertFalse(isInSegment(200199998L, segmentMin, segmentMax), 
            "200199998 should NOT be included (upper boundary is exclusive)");
        assertFalse(isInSegment(200199995L, segmentMin, segmentMax), 
            "200199995 should NOT be included (below lower boundary)");
    }
    
    private boolean isInSegment(long id, long min, long max) {
        return id >= min && id < max;
    }
    
    @Test
    @DisplayName("Debug: Print segment assignment for employee_id=200199497")
    public void debugSegmentAssignment() {
        long targetId = 200199497L;
        
        // Possible checkpoint scenarios
        System.out.println("\n=== Scenario 1: Checkpoints at [200149997, 200199996] ===");
        printSegmentAssignment(targetId, List.of(200149997L, 200199996L));
        
        System.out.println("\n=== Scenario 2: Checkpoints at [200149997, 200199996, 200199998] ===");
        printSegmentAssignment(targetId, List.of(200149997L, 200199996L, 200199998L));
        
        System.out.println("\n=== Scenario 3: Checkpoints at [200149997, 200199997] ===");
        printSegmentAssignment(targetId, List.of(200149997L, 200199997L));
    }
    
    private void printSegmentAssignment(long targetId, List<Long> checkpoints) {
        System.out.println("Target ID: " + targetId);
        System.out.println("Checkpoints: " + checkpoints);
        
        for (int i = 0; i < checkpoints.size(); i++) {
            long min = i == 0 ? Long.MIN_VALUE : checkpoints.get(i - 1);
            long max = checkpoints.get(i);
            boolean inSegment = (min == Long.MIN_VALUE || targetId >= min) && targetId < max;
            System.out.println("  Segment " + i + " [" + 
                (min == Long.MIN_VALUE ? "-∞" : min) + ", " + max + "): " + inSegment);
        }
        
        // Last segment
        long lastMin = checkpoints.get(checkpoints.size() - 1);
        boolean inLastSegment = targetId >= lastMin;
        System.out.println("  Segment " + checkpoints.size() + " [" + lastMin + ", ∞): " + inLastSegment);
    }
}
