package com.ragforge.mybatis.handler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class LongArrayTypeHandler extends BaseTypeHandler<Long[]> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Long[] parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("int8", parameter);
    ps.setArray(i, array);
  }

  @Override
  public Long[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toLongArray(rs.getArray(columnName));
  }

  @Override
  public Long[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toLongArray(rs.getArray(columnIndex));
  }

  @Override
  public Long[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toLongArray(cs.getArray(columnIndex));
  }

  private Long[] toLongArray(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    try {
      Object[] values = (Object[]) array.getArray();
      Long[] result = new Long[values.length];
      for (int i = 0; i < values.length; i++) {
        Object value = values[i];
        if (value instanceof Number number) {
          result[i] = number.longValue();
        } else if (value != null) {
          result[i] = Long.valueOf(String.valueOf(value));
        }
      }
      return result;
    } finally {
      array.free();
    }
  }
}
