package ch.cern.db.audit.flume.source.reader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import oracle.jdbc.driver.OracleConnection;
import oracle.jdbc.pool.OracleDataSource;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.cern.db.audit.flume.AuditEvent;
import ch.cern.db.audit.flume.source.deserializer.AuditEventDeserializer;
import ch.cern.db.audit.flume.source.deserializer.AuditEventDeserializerBuilderFactory;

import com.google.common.base.Preconditions;

public class ReliableOracleAuditEventReader implements ReliableEventReader {

	private static final Logger LOG = LoggerFactory.getLogger(ReliableOracleAuditEventReader.class);
	
	public static final String CONNECTION_URL_DEFAULT = "jdbc:oracle:oci:@";
	public static final String CONNECTION_URL_PARAM = "reader.connection_url";
	private String connection_url = CONNECTION_URL_DEFAULT;

	public static final String COMMITTING_FILE_PATH_DEFAULT = "committed_value.backup";
	public static final String COMMITTING_FILE_PATH_PARAM = "reader.committing_file";
	private String committing_file_path = COMMITTING_FILE_PATH_DEFAULT;
	private File committing_file = null;

	public static final String DESERIALIZER_DEFAULT = AuditEventDeserializerBuilderFactory.Types.JSON.toString();
	public static final String DESERIALIZER_PARAM = "deserializer";
	private AuditEventDeserializer deserializer;
	
	public static final String TABLE_NAME_PARAM = "reader.table";
	private String tableName = null;
	
	public static final String COLUMN_TO_COMMIT_PARAM = "reader.table.columnToCommit";
	private String columnToCommit = null;
	protected String committed_value = null;
	
	enum ColumnType {STRING, TIMESTAMP, NUMERIC}
	public static final String TYPE_COLUMN_TO_COMMIT_PARAM = "reader.table.columnToCommit.type";
	public static final ColumnType TYPE_COLUMN_TO_COMMIT_DEFUALT = ColumnType.TIMESTAMP;
	private ColumnType type_column_to_commit = TYPE_COLUMN_TO_COMMIT_DEFUALT;

	public static final String QUERY_PARAM = "reader.query";
	private String configuredQuery = null;

	public static final String LOGIN_AS_SYSDBA_PARAM = "reader.loginAsSysdba";
	public static final Boolean LOGIN_AS_SYSDBA_DEFAULT = false;
	
	private OracleDataSource dataSource = null;
	private Connection connection = null;
	private ResultSet resultSet = null;
	private Statement statement = null;
	
	protected String last_value = null;
	
	public ReliableOracleAuditEventReader(Context context) {
		tableName = context.getString(TABLE_NAME_PARAM);
		columnToCommit = context.getString(COLUMN_TO_COMMIT_PARAM);
		configuredQuery = context.getString(QUERY_PARAM);
		
		Preconditions.checkNotNull(columnToCommit, "Column to commit needs to be configured with " + COLUMN_TO_COMMIT_PARAM);
		
		if(configuredQuery == null){
			Preconditions.checkNotNull(tableName, "Table name needs to be configured with " + TABLE_NAME_PARAM);
		}
		
		String conf_type_column = context.getString(TYPE_COLUMN_TO_COMMIT_PARAM);
		if(conf_type_column != null){
			try{
				type_column_to_commit = ColumnType.valueOf(conf_type_column.toUpperCase());
			}catch(Exception e){
				throw new FlumeException("Configuration value for " + TYPE_COLUMN_TO_COMMIT_PARAM
						+ " is not valid, it must be one of: " + Arrays.asList(ColumnType.values()));
			}
		}
		
		Properties prop = new Properties();
		prop.put(OracleConnection.CONNECTION_PROPERTY_USER_NAME, "sys");
		prop.put(OracleConnection.CONNECTION_PROPERTY_PASSWORD, "sys");
		
		if(context.getBoolean(LOGIN_AS_SYSDBA_PARAM, LOGIN_AS_SYSDBA_DEFAULT)){
			prop.put(OracleConnection.CONNECTION_PROPERTY_INTERNAL_LOGON, "sysdba");
		}
		
		connection_url = context.getString(CONNECTION_URL_PARAM, CONNECTION_URL_DEFAULT);
		try {
			dataSource = new OracleDataSource();
			dataSource.setConnectionProperties(prop);
			dataSource.setURL(connection_url);
		} catch (SQLException e) {
			throw new FlumeException(e.getMessage(), e);
		}
		
		String des_config = context.getString(DESERIALIZER_PARAM, DESERIALIZER_DEFAULT);
		AuditEventDeserializer.Builder builder = AuditEventDeserializerBuilderFactory.newInstance(des_config);
        builder.configure(context);
		this.deserializer = builder.build();
		LOG.info("Using deserializer: " + this.deserializer.getClass().getName());
		
		committing_file = new File(committing_file_path);
		
		loadLastCommittedValue();
	}

	private void loadLastCommittedValue() {
		try {
			if(committing_file.exists()){
				FileReader in = new FileReader(committing_file);
				char [] in_chars = new char[60];
			    in.read(in_chars);
				in.close();
				String value_from_file = new String(in_chars).trim();
				
				if(value_from_file.length() > 1){
					committed_value = value_from_file;
					
					LOG.info("Last value loaded from file: " + committed_value);
				}else{
					LOG.info("File for loading last value is empty");
				}
			}else{
				committing_file.createNewFile();
				
				LOG.info("File for storing last commited value have been created: " +
						committing_file.getAbsolutePath());
			}
		} catch (IOException e) {
			throw new FlumeException(e);
		}
	}

	@Override
	public Event readEvent() throws IOException {
		try {
			if(resultSet == null)
				runQuery();
			
			if(resultSet != null && !resultSet.isClosed() && resultSet.next()){
				ResultSetMetaData metadata = resultSet.getMetaData();
				int columnCount = metadata.getColumnCount();
				
				AuditEvent event = new AuditEvent();
				
				for (int i = 1; i <= columnCount; i++) {										
					String name = metadata.getColumnName(i);
					
					switch (metadata.getColumnType(i)) {
					case java.sql.Types.SMALLINT:
					case java.sql.Types.TINYINT:
					case java.sql.Types.INTEGER:
					case java.sql.Types.BIGINT:
						event.addField(name, resultSet.getInt(i));
						break;
					case java.sql.Types.BOOLEAN:
						event.addField(name, resultSet.getBoolean(i));
						break;
					case java.sql.Types.NUMERIC:
					case java.sql.Types.DOUBLE:
					case java.sql.Types.FLOAT:
						event.addField(name, resultSet.getDouble(i));
						break;
					case java.sql.Types.TIMESTAMP:
					case -102: //TIMESTAMP(6) WITH LOCAL TIME ZONE
						String ts = resultSet.getTimestamp(i).toString();
						ts = ts.substring(0, 23).replace(" ", "T");
						event.addField(name, ts);
						break;
					default:
						event.addField(name, resultSet.getString(i));
						break;
					}
					
					if(name.equals(columnToCommit)){
						last_value = resultSet.getString(i);
					}
				}				
				
				return deserializer.process(event);
			}else{
				resultSet = null;
				
				return null;
			}
		} catch (SQLException e) {
			throw new IOException(e);
		}
	}

	private void runQuery() throws SQLException {	
		if(statement != null)
			statement.close();
		
		connect();
		
		statement = connection.createStatement();
		
		String query = createQuery(committed_value);
		
		resultSet = statement.executeQuery(query);
		
		LOG.info("Executing query: " + query);
	}

	protected String createQuery(String committed_value) {
		
		if(configuredQuery != null){
			char left = '[';
			char right = ']';
			String toReplace = "{$committed_value}";
			
			int left_index = configuredQuery.indexOf(left);
			int right_index = configuredQuery.indexOf(right);
			int committed_value_index = configuredQuery.indexOf(toReplace);
			if(committed_value_index < right_index && committed_value_index > left_index){
				//right syntax for replacing 
				//SELECT * FROM table_name [WHERE column_name > '{$committed_vale}'] ORDER BY column_name
				
				if(committed_value == null){
					return configuredQuery.substring(0, left_index)
							.concat(configuredQuery.substring(right_index+1, configuredQuery.length()));
				}else{
					return configuredQuery.replace(left, ' ')
							.replace(right, ' ')
							.replace(toReplace, committed_value);
				}
			}else{
				return configuredQuery;
			}
		}
		
		String query = "SELECT * FROM " + tableName;
		
		if(committed_value != null){
			query = query.concat(" WHERE " + columnToCommit + " > ");
		
			switch (type_column_to_commit) {
			case NUMERIC:
				query = query.concat(committed_value);
				break;
			case TIMESTAMP:
				query = query.concat("TIMESTAMP \'" + committed_value + "\'");
				break;
			default: //String
				query = query.concat("\'" + committed_value + "\'");
				break;
			}
		}
		
		query = query.concat(" ORDER BY " + columnToCommit);
		
		return query;
	}

	private void connect() throws SQLException{
		try {
			if(connection == null || connection.isClosed())
				connection = dataSource.getConnection();
			
		} catch (SQLException e) {
			LOG.error(e.getMessage(), e);
			throw e;
		}
	}
	
	@Override
	public List<Event> readEvents(int numberOfEventToRead) throws IOException {
		LinkedList<Event> events = new LinkedList<Event>();
		
		for (int i = 0; i < numberOfEventToRead; i++){
			Event event = readEvent();
			
			if(event != null)
				events.add(event);
			else{
				LOG.info("Number of events returned: " + events.size());
				return events;
			}
		}
		
		LOG.info("Number of events returned: " + events.size());
		return events;
	}

	@Override
	public void commit() throws IOException {
		if(last_value == null)
			return;
		
		committed_value = last_value;
		
		FileWriter out = new FileWriter(committing_file, false);
		out.write(committed_value);
		out.close();
		
		last_value = null;
	}

	@Override
	public void close() throws IOException {
		try {
			connection.close();
			statement.close();
		} catch (Throwable e) {
		}
	}

	public static class Builder implements ReliableEventReader.Builder {

		private Context context;
		
		@Override
		public void configure(Context context) {
			this.context = context;
		}

		@Override
		public ReliableOracleAuditEventReader build() {
			return new ReliableOracleAuditEventReader(context);
		}
		
	}
}
