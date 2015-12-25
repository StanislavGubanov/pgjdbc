/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc;

import java.sql.*;
import java.util.Map;

class PgCallableStatement extends PgPreparedStatement implements CallableStatement
{
    PgCallableStatement(PgConnection connection, String sql, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, true, rsType, rsConcurrency, rsHoldability);
    }

    public Object getObject(int i, Map<String, Class<?>> map) throws SQLException
    {
        return getObjectImpl(i, map);
    }

    public Object getObject(String s, Map<String, Class<?>> map) throws SQLException
    {
        return getObjectImpl(s, map);
    }

}