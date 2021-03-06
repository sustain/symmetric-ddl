package org.jumpmind.symmetric.ddl.platform.oracle;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.collections.map.ListOrderedMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.jumpmind.symmetric.ddl.DdlUtilsException;
import org.jumpmind.symmetric.ddl.Platform;
import org.jumpmind.symmetric.ddl.model.Column;
import org.jumpmind.symmetric.ddl.model.Table;
import org.jumpmind.symmetric.ddl.model.TypeMap;
import org.jumpmind.symmetric.ddl.platform.DatabaseMetaDataWrapper;
import org.jumpmind.symmetric.ddl.platform.JdbcModelReader;

/*
 * Reads a database model from an Oracle 8 database.
 */
public class OracleModelReader extends JdbcModelReader
{
    private final Log _log = LogFactory.getLog(OracleModelReader.class);
    
	/* The regular expression pattern for the Oracle conversion of ISO dates. */
	private Pattern _oracleIsoDatePattern;
	/* The regular expression pattern for the Oracle conversion of ISO times. */
	private Pattern _oracleIsoTimePattern;
	/* The regular expression pattern for the Oracle conversion of ISO timestamps. */
	private Pattern _oracleIsoTimestampPattern;

	/*
     * Creates a new model reader for Oracle 8 databases.
     * 
     * @param platform The platform that this model reader belongs to
     */
    public OracleModelReader(Platform platform)
    {
        super(platform);
        setDefaultCatalogPattern(null);
        setDefaultSchemaPattern(null);
        setDefaultTablePattern("%");

        PatternCompiler compiler = new Perl5Compiler();

    	try
    	{
    		_oracleIsoDatePattern      = compiler.compile("TO_DATE\\('([^']*)'\\, 'YYYY\\-MM\\-DD'\\)");
    		_oracleIsoTimePattern      = compiler.compile("TO_DATE\\('([^']*)'\\, 'HH24:MI:SS'\\)");
    		_oracleIsoTimestampPattern = compiler.compile("TO_DATE\\('([^']*)'\\, 'YYYY\\-MM\\-DD HH24:MI:SS'\\)");
        }
    	catch (MalformedPatternException ex)
        {
        	throw new DdlUtilsException(ex);
        }
    }
    
    @Override
    protected Table readTable(Connection connection, DatabaseMetaDataWrapper metaData, Map values)
            throws SQLException {
        // Oracle 10 added the recycle bin which contains dropped database
        // objects not yet purged
        // Since we don't want entries from the recycle bin, we filter them out
        boolean tableHasBeenDeleted = isTableInRecycleBin(connection, values);

        if (!tableHasBeenDeleted) {
            String tableName = (String) values.get("TABLE_NAME");

            // system table ?
            if (tableName.indexOf('$') > 0) {
                return null;
            }

            Table table = super.readTable(connection, metaData, values);
            if (table != null) {
                determineAutoIncrementColumns(connection, table);
            }

            return table;
        } else {
            return null;
        }
    }
    
    protected boolean isTableInRecycleBin(Connection connection, Map values) throws SQLException {
        ResultSet rs = null;
        PreparedStatement stmt = null;
        try {
            stmt = connection.prepareStatement("SELECT * FROM RECYCLEBIN WHERE OBJECT_NAME=?");
            stmt.setString(1, (String) values.get("TABLE_NAME"));

            rs = stmt.executeQuery();
            return rs.next();
        } finally {
            close(rs);
            close(stmt);
        }
    }
    
    @Override
    protected Integer overrideJdbcTypeForColumn(Map<String,Object> values) {
        String typeName = (String) values.get("TYPE_NAME");
        if (typeName != null && typeName.startsWith("DATE")) {
            return Types.DATE;
        } else if (typeName != null && typeName.startsWith("TIMESTAMP") && !typeName.endsWith("TIME ZONE")) {
            // This is for Oracle's TIMESTAMP(9)
            return Types.TIMESTAMP;
        } else if (typeName != null && typeName.startsWith("NVARCHAR")) {
            // This is for Oracle's NVARCHAR type
            return Types.VARCHAR;
        } else if (typeName != null && typeName.startsWith("LONGNVARCHAR")) {
            return Types.LONGVARCHAR;            
        } else if (typeName != null && typeName.startsWith("NCHAR")) {
            return Types.CHAR;
        } else if (typeName != null && typeName.startsWith("NCLOB")) {
            return Types.CLOB;
        } else if (typeName != null && typeName.startsWith("BINARY_FLOAT")) {
            return Types.FLOAT;
        } else if (typeName != null && typeName.startsWith("BINARY_DOUBLE")) {
            return Types.DOUBLE;
        } else {
            return super.overrideJdbcTypeForColumn(values);
        }
    }

    @Override
    protected Column readColumn(DatabaseMetaDataWrapper metaData, Map values) throws SQLException
    {
		Column column = super.readColumn(metaData, values);
		if (column.getTypeCode() == Types.DECIMAL)
		{
			// We're back-mapping the NUMBER columns returned by Oracle
			// Note that the JDBC driver returns DECIMAL for these NUMBER columns
			switch (column.getSizeAsInt())
			{
				case 5:
					if (column.getScale() == 0)
					{
						column.setTypeCode(Types.SMALLINT);
					}
					break;
				case 18:
					column.setTypeCode(Types.REAL);
					break;
				case 22:
					if (column.getScale() == 0)
					{
						column.setTypeCode(Types.INTEGER);
					}
					break;
				case 38:
					if (column.getScale() == 0)
					{
						column.setTypeCode(Types.BIGINT);
					}
					else
					{
						column.setTypeCode(Types.DOUBLE);
					}
					break;
			}
		}
		else if (column.getTypeCode() == Types.FLOAT)
		{
			// Same for REAL, FLOAT, DOUBLE PRECISION, which all back-map to FLOAT but with
			// different sizes (63 for REAL, 126 for FLOAT/DOUBLE PRECISION)
			switch (column.getSizeAsInt())
			{
				case 63:
					column.setTypeCode(Types.REAL);
					break;
				case 126:
					column.setTypeCode(Types.DOUBLE);
					break;
			}
		}
		else if ((column.getTypeCode() == Types.DATE) || (column.getTypeCode() == Types.TIMESTAMP))
		{
			// we also reverse the ISO-format adaptation, and adjust the default value to timestamp
			if (column.getDefaultValue() != null)
			{
				PatternMatcher matcher   = new Perl5Matcher();
				Timestamp      timestamp = null;
	
				if (matcher.matches(column.getDefaultValue(), _oracleIsoTimestampPattern))
				{
					String timestampVal = matcher.getMatch().group(1);

					timestamp = Timestamp.valueOf(timestampVal);
				}
				else if (matcher.matches(column.getDefaultValue(), _oracleIsoDatePattern))
				{
					String dateVal = matcher.getMatch().group(1);

					timestamp = new Timestamp(Date.valueOf(dateVal).getTime());
				}
				else if (matcher.matches(column.getDefaultValue(), _oracleIsoTimePattern))
				{
					String timeVal = matcher.getMatch().group(1);

					timestamp = new Timestamp(Time.valueOf(timeVal).getTime());
				}
				if (timestamp != null)
				{
					column.setDefaultValue(timestamp.toString());
				}
			}
		}
        else if (TypeMap.isTextType(column.getTypeCode()))
        {
            column.setDefaultValue(unescape(column.getDefaultValue(), "'", "''"));
        }
		return column;
	}

    /*
     * Helper method that determines the auto increment status using Firebird's system tables.
     *
     * @param table The table
     */
    protected void determineAutoIncrementColumns(Connection connection, Table table) throws SQLException
    {
        Column[] columns = table.getColumns();

        for (int idx = 0; idx < columns.length; idx++)
        {
            columns[idx].setAutoIncrement(isAutoIncrement(connection, table, columns[idx]));
        }
    }

    /*
     * Tries to determine whether the given column is an identity column.
     * 
     * @param table  The table
     * @param column The column
     * @return <code>true</code> if the column is an identity column
     */
    protected boolean isAutoIncrement(Connection connection, Table table, Column column) throws SQLException
    {
        // TODO: For now, we only check whether there is a sequence & trigger as generated by DdlUtils
        //       But once sequence/trigger support is in place, it might be possible to 'parse' the
        //       trigger body (via SELECT trigger_name, trigger_body FROM user_triggers) in order to
        //       determine whether it fits our auto-increment definition
        PreparedStatement prepStmt    = null;
        String            triggerName = getPlatform().getSqlBuilder().getConstraintName("trg", table, column.getName(), null);
        String            seqName     = getPlatform().getSqlBuilder().getConstraintName("seq", table, column.getName(), null);

        if (!getPlatform().isDelimitedIdentifierModeOn())
        {
            triggerName = triggerName.toUpperCase();
            seqName     = seqName.toUpperCase();
        }
        try
        {
            prepStmt = connection.prepareStatement("SELECT * FROM user_triggers WHERE trigger_name = ?");
            prepStmt.setString(1, triggerName);

            ResultSet resultSet = prepStmt.executeQuery();

            if (!resultSet.next())
            {
                return false;
            }
            // we have a trigger, so lets check the sequence
            prepStmt.close();

            prepStmt = connection.prepareStatement("SELECT * FROM user_sequences WHERE sequence_name = ?");
            prepStmt.setString(1, seqName);

            resultSet = prepStmt.executeQuery();
            return resultSet.next();
        }
        finally
        {
            if (prepStmt != null)
            {
                prepStmt.close();
            }
        }
    }

    @Override
    protected Collection readIndices(Connection connection, DatabaseMetaDataWrapper metaData, String tableName) throws SQLException
    {
        // Oracle bug 4999817 causes a table analyze to execute in response to a call to 
    // DatabaseMetaData#getIndexInfo.
    // The bug is fixed in driver version 10.2.0.4.  The bug is present in at least
    // driver versions 10.2.0.1.0, 10.1.0.2.0, and 9.2.0.5.
    // To avoid this bug, we will access user_indexes view.
        // This also allows us to filter system-generated indices which are identified by either
        // having GENERATED='Y' in the query result, or by their index names being equal to the
        // name of the primary key of the table

        StringBuffer query = new StringBuffer();

        query.append("SELECT a.INDEX_NAME, a.INDEX_TYPE, a.UNIQUENESS, b.COLUMN_NAME, b.COLUMN_POSITION FROM USER_INDEXES a, USER_IND_COLUMNS b WHERE ");
        query.append("a.TABLE_NAME=? AND a.GENERATED=? AND a.TABLE_TYPE=? AND a.TABLE_NAME=b.TABLE_NAME AND a.INDEX_NAME=b.INDEX_NAME AND ");
        query.append("a.INDEX_NAME NOT IN (SELECT DISTINCT c.CONSTRAINT_NAME FROM USER_CONSTRAINTS c WHERE c.CONSTRAINT_TYPE=? AND c.TABLE_NAME=a.TABLE_NAME");
        if (metaData.getSchemaPattern() != null)
        {
            query.append(" AND c.OWNER LIKE ?) AND a.TABLE_OWNER LIKE ?");
        }
        else
        {
            query.append(")");
        }

        Map               indices = new ListOrderedMap();
        PreparedStatement stmt    = null;

        try
        {
            stmt = connection.prepareStatement(query.toString());
            stmt.setString(1, getPlatform().isDelimitedIdentifierModeOn() ? tableName : tableName.toUpperCase());
            stmt.setString(2, "N");
            stmt.setString(3, "TABLE");
            stmt.setString(4, "P");
            if (metaData.getSchemaPattern() != null)
            {
                stmt.setString(5, metaData.getSchemaPattern().toUpperCase());
                stmt.setString(6, metaData.getSchemaPattern().toUpperCase());
            }

            ResultSet rs     = stmt.executeQuery();
            Map       values = new HashMap();

            while (rs.next())
            {        
                String name =rs.getString(1);                
                String type = rs.getString(2);
                // Only read in normal oracle indexes
                if (type.startsWith("NORMAL"))  
                {
                    values.put("INDEX_TYPE",       new Short(DatabaseMetaData.tableIndexOther));
                    values.put("INDEX_NAME",       name);    
                    values.put("NON_UNIQUE",       "UNIQUE".equalsIgnoreCase(rs.getString(3)) ? Boolean.FALSE : Boolean.TRUE);
                    values.put("COLUMN_NAME",      rs.getString(4));
                    values.put("ORDINAL_POSITION", new Short(rs.getShort(5)));

                    readIndex(metaData, values, indices);
                } else {
                    _log.warn("Skipping index " + name + " of type " + type);
                }
            }
        }
        finally
        {
            if (stmt != null)
            {
                stmt.close();
            }
        }
        return indices.values();
    }
}
