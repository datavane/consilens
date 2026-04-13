package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test case to reproduce the date comparison bug where different dates
 * (1992-02-10 vs 1992-02-19) are not detected as different.
 */
public class DateComparisonBugTest {

    @Test
    public void testDateComparisonBug() {
        // Simulate the problematic row from employee_detail and employee_profile
        // employee_id=200199497, birth_date differs: 1992-02-10 vs 1992-02-19

        List<String> keyColumns = Arrays.asList("employee_id", "employee_code");
        List<String> compareColumns = Arrays.asList("first_name", "last_name", "email", "phone", 
                "birth_date", "hire_date", "department", "position", "salary", "address", 
                "city", "province", "postal_code");

        // Row from employee_detail (source)
        Object[] row1 = new Object[]{
                200199497,           // employee_id
                "EMP200199497",      // employee_code
                "John",              // first_name
                "Doe",               // last_name
                "john.doe@test.com", // email
                "1234567890",        // phone
                "1992-02-10",        // birth_date - SOURCE
                "2020-01-15",        // hire_date
                "Engineering",       // department
                "Engineer",          // position
                50000.00,            // salary
                "123 Main St",       // address
                "Beijing",           // city
                "Beijing",           // province
                "100000"             // postal_code
        };

        // Row from employee_profile (target)
        Object[] row2 = new Object[]{
                200199497,           // employee_id
                "EMP200199497",      // employee_code
                "John",              // first_name
                "Doe",               // last_name
                "john.doe@test.com", // email
                "1234567890",        // phone
                "1992-02-19",        // birth_date - TARGET (DIFFERENT!)
                "2020-01-15",        // hire_date
                "Engineering",       // department
                "Engineer",          // position
                50000.00,            // salary
                "123 Main St",       // address
                "Beijing",           // city
                "Beijing",           // province
                "100000"             // postal_code
        };

        // Set up column types
        Map<String, DataType> columnTypes = new HashMap<>();
        columnTypes.put("employee_id", DataType.INTEGER);
        columnTypes.put("employee_code", DataType.VARCHAR);
        columnTypes.put("first_name", DataType.VARCHAR);
        columnTypes.put("last_name", DataType.VARCHAR);
        columnTypes.put("email", DataType.VARCHAR);
        columnTypes.put("phone", DataType.VARCHAR);
        columnTypes.put("birth_date", DataType.DATE);  // This is the key column type
        columnTypes.put("hire_date", DataType.DATE);
        columnTypes.put("department", DataType.VARCHAR);
        columnTypes.put("position", DataType.VARCHAR);
        columnTypes.put("salary", DataType.DECIMAL);
        columnTypes.put("address", DataType.VARCHAR);
        columnTypes.put("city", DataType.VARCHAR);
        columnTypes.put("province", DataType.VARCHAR);
        columnTypes.put("postal_code", DataType.VARCHAR);

        List<String> allColumns = new ArrayList<>(keyColumns);
        allColumns.addAll(compareColumns);

        // Run the comparison
        List<DiffRow> differences = LocalDiffEngine.findDifferences(
                Collections.singletonList(row1),
                Collections.singletonList(row2),
                keyColumns,
                compareColumns,
                keyColumns,
                compareColumns,
                columnTypes,
                columnTypes
        );

        // Assert that the difference is detected
        assertThat(differences)
                .as("Should detect the birth_date difference between 1992-02-10 and 1992-02-19")
                .isNotEmpty();

        if (!differences.isEmpty()) {
            DiffRow diff = differences.get(0);
            assertThat(diff.getOperation()).isEqualTo(DiffOperation.MISMATCH);
            assertThat(diff.getChangedColumns1()).contains("birth_date");
            assertThat(diff.getChangedColumns2()).contains("birth_date");
        }
    }

    @Test
    public void testValueNormalizerDateNormalization() {
        // Test that ValueNormalizer correctly normalizes different date formats
        String date1 = "1992-02-10";
        String date2 = "1992-02-19";

        String normalized1 = ValueNormalizer.normalizeValue(date1, DataType.DATE);
        String normalized2 = ValueNormalizer.normalizeValue(date2, DataType.DATE);

        System.out.println("Normalized date1: " + normalized1);
        System.out.println("Normalized date2: " + normalized2);

        assertThat(normalized1).isEqualTo("1992-02-10");
        assertThat(normalized2).isEqualTo("1992-02-19");
        assertThat(normalized1).isNotEqualTo(normalized2);
    }
}
