package com.ragforge.mybatis.handler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("varchar", parameter);
    ps.setArray(i, array);
  }

  @Override
  public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toStringArray(rs.getArray(columnName));
  }

  @Override
  public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toStringArray(rs.getArray(columnIndex));
  }

  @Override
  public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toStringArray(cs.getArray(columnIndex));
  }

  private String[] toStringArray(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    try {
      Object raw = array.getArray();
      if (raw instanceof String[] strings) {
        return strings;
      }
      Object[] values = (Object[]) raw;
      String[] result = new String[values.length];
      for (int i = 0; i < values.length; i++) {
        result[i] = values[i] == null ? null : String.valueOf(values[i]);
      }
      return result;
    } finally {
      array.free();
    }
  }
}
