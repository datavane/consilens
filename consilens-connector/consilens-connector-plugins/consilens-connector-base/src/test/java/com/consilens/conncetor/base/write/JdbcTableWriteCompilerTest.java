package com.consilens.conncetor.base.write;

import com.consilens.common.enums.DataType;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.write.PreparedWriteRow;
import com.consilens.connector.api.write.TableWritePlan;
import com.consilens.connector.api.write.TypedOutputRow;
import com.consilens.connector.api.write.TypedOutputValue;
import com.consilens.connector.api.write.WriteColumnSpec;
import com.consilens.connector.api.model.ConnectorNativeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcTableWriteCompilerTest {

    @Test
    void typeDefinitionKeepsLeftmostConstraintOnly() {
        JdbcTableWriteCompiler compiler = new JdbcTableWriteCompiler(null);

        // 关键字列表顺序 ≠ 出现顺序：DEFAULT 比 NOT NULL 靠左，必须从 DEFAULT 处切
        assertEquals("INT", compiler.extractTypeDefinition("INT DEFAULT 5 NOT NULL"));
        // "DEFAULT NULL" 里的 NULL 不是独立约束，不能切在 NULL 上
        assertEquals("VARCHAR(20)", compiler.extractTypeDefinition("VARCHAR(20) DEFAULT NULL"));
        assertEquals("VARCHAR(20)", compiler.extractTypeDefinition("VARCHAR(20) NOT NULL"));
        assertEquals("DECIMAL(10,2)", compiler.extractTypeDefinition("DECIMAL(10,2)"));
        // 括号/引号内的关键字不参与匹配
        assertEquals("ENUM('a','PRIMARY KEY')",
                compiler.extractTypeDefinition("ENUM('a','PRIMARY KEY') NOT NULL"));
        assertEquals("VARCHAR(20)",
                compiler.extractTypeDefinition("VARCHAR(20) CHECK (amount > 0) NOT NULL"));
        assertEquals("TIMESTAMP", compiler.extractTypeDefinition("TIMESTAMP DEFAULT CURRENT_TIMESTAMP"));
        // 字符集/排序规则/显示属性也是列属性，不能留在类型定义里（否则 convertToTypeDescriptor 认不出来）
        assertEquals("VARCHAR(20)",
                compiler.extractTypeDefinition("VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"));
        assertEquals("TIMESTAMP(6)",
                compiler.extractTypeDefinition("TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)"));
    }

    @Test
    void stringValuesStayTextForVarcharColumns() {
        TypeDescriptor varchar = TypeDescriptor.builder(DataType.STRING_TYPE).build();
        WriteColumnSpec column = new WriteColumnSpec("name", varchar, varchar,
                ConnectorNativeType.builder().jdbcType(java.sql.Types.VARCHAR).build(), true);
        TableWritePlan plan = new TableWritePlan("postgresql", null, null, null, List.of(column));

        PreparedWriteRow row = new JdbcTableWriteCompiler(null).prepareRow(
                new TypedOutputRow(List.of(new TypedOutputValue("name", varchar, "张三"))), plan);

        // 不能变成 byte[]：否则绑定到 VARCHAR 参数会触发类型不匹配（PostgreSQL 直接报错）
        assertEquals("张三", row.getValues().get(0).getValue());
    }

    @Test
    void textColumnsDecodeValidUtf8AndFallBackToHexForInvalidBytes() {
        TypeDescriptor varchar = TypeDescriptor.builder(DataType.STRING_TYPE).build();
        WriteColumnSpec column = new WriteColumnSpec("name", varchar, varchar,
                ConnectorNativeType.builder().jdbcType(java.sql.Types.VARCHAR).build(), true);
        TableWritePlan plan = new TableWritePlan("mysql", null, null, null, List.of(column));
        JdbcTableWriteCompiler compiler = new JdbcTableWriteCompiler(null);

        PreparedWriteRow valid = compiler.prepareRow(new TypedOutputRow(List.of(
                new TypedOutputValue("name", varchar, new byte[]{0x41, 0x42}))), plan);
        // 非法 UTF-8 不能解成替换字符：不同字节会归一化成同一个字符串
        PreparedWriteRow invalid = compiler.prepareRow(new TypedOutputRow(List.of(
                new TypedOutputValue("name", varchar, new byte[]{(byte) 0xC3, 0x28}))), plan);

        assertEquals("AB", valid.getValues().get(0).getValue());
        assertEquals("C328", invalid.getValues().get(0).getValue());
    }

    @Test
    void binaryValuesStayBytesInsteadOfObjectToString() {
        TypeDescriptor binary = TypeDescriptor.builder(DataType.BINARY_TYPE).build();
        WriteColumnSpec column = new WriteColumnSpec("payload", binary, binary,
                ConnectorNativeType.builder().jdbcType(java.sql.Types.BINARY).build(), true);
        TableWritePlan plan = new TableWritePlan("mysql", null, null, null, List.of(column));
        byte[] payload = new byte[]{1, 2, 3};

        PreparedWriteRow row = new JdbcTableWriteCompiler(null).prepareRow(
                new TypedOutputRow(List.of(new TypedOutputValue("payload", binary, payload))), plan);

        assertArrayEquals(payload, (byte[]) row.getValues().get(0).getValue());
    }

    @Test
    void narrowingToSmallIntOrIntMustBeExact() {
        // 70000 曾经被 shortValue() 静默截成 4464：行插进去了，值却是错的
        assertThrows(IllegalArgumentException.class,
                () -> JdbcTableWriteCompiler.exactIntegral(70000L, Short.MIN_VALUE, Short.MAX_VALUE, "SMALLINT"));
        // 带小数不能悄悄取整
        assertThrows(IllegalArgumentException.class,
                () -> JdbcTableWriteCompiler.exactIntegral(new BigDecimal("1.5"),
                        Short.MIN_VALUE, Short.MAX_VALUE, "SMALLINT"));
        // 超大整数溢出 BigDecimal.longValueExact()
        assertThrows(IllegalArgumentException.class,
                () -> JdbcTableWriteCompiler.exactIntegral(new BigDecimal("99999999999999999999"),
                        Integer.MIN_VALUE, Integer.MAX_VALUE, "INTEGER"));

        assertEquals(-12, JdbcTableWriteCompiler.exactIntegral(new BigDecimal("12.00"),
                Integer.MIN_VALUE, Integer.MAX_VALUE, "INTEGER") * -1);
        assertEquals(2147483647L, JdbcTableWriteCompiler.exactIntegral(2147483647L,
                Integer.MIN_VALUE, Integer.MAX_VALUE, "INTEGER"));
    }
}
