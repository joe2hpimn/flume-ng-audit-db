# Apache Flume customizations for developing a secure and universal database auditing platform

A highly scalable, secure and central repository that stores consolidated audit data and optionally listener, 
alert and OS log events generated by the database instances. This central platform will be used for reporting, 
alerting and security policy management. The reports will provide a holistic view of activity across all databases 
and will include compliance reports, activity reports and privilege reports. The alerting mechanism will 
detect and alert on abnormal activity, potential intrusion and much more. As audit data is vital record of 
activity, to protect this information the central repository will reside outside of existing databases and most 
likely in Hadoop eco-system.

## Developed components

Several implementations have been made for adapting Flume, both in the source and sink side.

* AuditSource: a custom source which is able to collect audit data from database instances. This source needs a reader and a deserializer, some are already implemented:
    * Readers, create AuditEvents from audit data:
        * ReliableJdbcAuditEventReader (default): reads audit events from tables. It uses JDBC driver for connecting to database, so many types of databases are compatible. It provides a reliable way to read audit data from tables in order to avoid data loss.
    * Deserializer, convert AuditEvents into Flume Events:
        * JSONAuditEventDeserializer (default): generates events which body is a JSON with all fields contained in AuditEvent.
        * TextAuditEventDeserializer: generates events which body is a string with all fields contained in AuditEvent.
* For some sinks, you may need to implement a custom parser for parsing Flume Events to Avro records:
    * JSONtoAvroParser: for Kite sink, this parser converts Flume Events which body is JSON into Avro records.
    * JSONtoElasticSearchEventSerializer: for Elasticsearch sink, this parser converts Flume Events which body is JSON into Elasticsearch XContentBuilder.
    
## Configuration

### AuditSource

In order to use AuditSource as source in your Flume agent, you need to specify the type of agent source as:

```
<agent_name>.sources.<source_name>.type = ch.cern.db.audit.flume.source.AuditSource 
```

You do not need to specify a reader if you are going to use ReliableJdbcAuditEventReader since this one is the default. If you want to use other other reader use the following parameter.

```
<agent_name>.sources.<source_name>.reader = jdbc
```

If you want to develop a custom Reader, make sure that it implements ReliableEventReader and create a nested Builder class. To use your custom reader, you need to configure .reader parameter with the FQCN.

Deserializer needs also to be configured. Use below parameter with json or text value.

```
<agent_name>.sources.<source_name>.deserializer = [json|text] 
```

If you want to develop a custom deserializer, make sure that it implements AuditEventDeserializer and create a nested Builder class. To use your custom deserializer, you need to configure .deserializer parameter with the FQCN.

### ReliableJdbcAuditEventReader

Find below all available configuration parameters:

```
reader.connectionDriver = oracle.jdbc.driver.OracleDriver
reader.connectionUrl = jdbc:oracle:oci:@
reader.username = sys as sysdba
reader.password = sys
reader.table = NULL
reader.table.columnToCommit = NULL
reader.table.columnToCommit.type = [TIMESTAMP (default)|NUMERIC|STRING]
reader.query = NULL
reader.committing_file = committed_value.backup
```

Default values are written, parameters with NULL has not default value. Most configuration parameters do not require any further explanation. However, some of then need to be explained.

Since it is a reliable reader, it requires one column of the table to be used for committing its value. Column to use for this purpose is configured with ".columnToCommit" parameter. You would need to specify the type of this column in order to build the query properly.

".table" or ".query" parameter must be configured. ".columnToCommit" is always required.

In case the query is not built properly or you want to use a custom one, you can use ".query" parameter. You should use the following syntax:

```
SELECT * FROM table_name [WHERE column_name > '{$committed_vale}'] ORDER BY column_name
```

Some tips:
* ORDER BY clause is strongly recommended to use since last value from "column to commit" will be used in further queries to get only last rows.
* If no value has been committed, part of the query between [] is removed.
* If there is no [], same query will be always executed.
* {$committed_value} must be between [], it will be replace by last committed value.

Custom query example:

```
reader.query = SELECT * FROM UNIFIED_AUDIT_TRAIL [WHERE EVENT_TIMESTAMP > TIMESTAMP '{$committed_value}'] ORDER BY EVENT_TIMESTAMP
```

### JSONAuditEventDeserializer, TextAuditEventDeserializer, JSONtoAvroParser and JSONtoElasticSearchEventSerializer

They do not have any configuration parameters.









 
