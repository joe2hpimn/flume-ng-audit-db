/**
 * Copyright (C) 2016, CERN
 * This software is distributed under the terms of the GNU General Public
 * Licence version 3 (GPL Version 3), copied verbatim in the file "LICENSE".
 * In applying this license, CERN does not waive the privileges and immunities
 * granted to it by virtue of its status as Intergovernmental Organization
 * or submit itself to any jurisdiction.
 */
package ch.cern.db.flume.sink.kite.parser;

import java.nio.charset.Charset;

import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.SchemaBuilder.FieldAssembler;
import org.apache.avro.generic.GenericRecord;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.sink.kite.NonRecoverableEventException;
import org.apache.flume.sink.kite.parser.EntityParser;
import org.junit.Assert;
import org.junit.Test;

import ch.cern.db.flume.sink.kite.parser.JSONtoAvroParser;
import ch.cern.db.flume.sink.kite.parser.JSONtoAvroParser.Builder;

public class JSONtoAvroParserTest {

	@Test
	public void fildsInJSONbutNotInSchema() throws EventDeliveryException, NonRecoverableEventException{
		FieldAssembler<Schema> builder = SchemaBuilder.record("audit").fields();
		builder.name("integer_field").type().nullable().intType().noDefault();
		builder.name("string_field").type().nullable().stringType().noDefault();
		Schema schema = builder.endRecord();
		
		Builder parser_builder = new JSONtoAvroParser.Builder();
		EntityParser<GenericRecord> parser = parser_builder.build(schema , null);
		
		String json = "{\"not_exist\":\"value\", \"integer_field\":10}";
		GenericRecord record = parser.parse(EventBuilder.withBody(json, Charset.defaultCharset()), null);
	
		Assert.assertEquals(10, record.get("integer_field"));
		Assert.assertNull(record.get("string_field"));
	}
	
}
