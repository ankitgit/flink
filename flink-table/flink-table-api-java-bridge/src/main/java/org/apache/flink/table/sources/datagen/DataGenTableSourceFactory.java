/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.sources.datagen;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions.OptionBuilder;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.streaming.api.functions.source.datagen.DataGenerator;
import org.apache.flink.streaming.api.functions.source.datagen.RandomGenerator;
import org.apache.flink.streaming.api.functions.source.datagen.SequenceGenerator;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.utils.TableSchemaUtils;

import java.util.HashSet;
import java.util.Set;

import static org.apache.flink.configuration.ConfigOptions.key;

/**
 * Factory for creating configured instances of {@link DataGenTableSource} in a stream environment.
 */
@Experimental
public class DataGenTableSourceFactory implements DynamicTableSourceFactory {

	public static final String IDENTIFIER = "datagen";

	public static final ConfigOption<Long> ROWS_PER_SECOND = key("rows-per-second")
			.longType()
			.defaultValue(Long.MAX_VALUE)
			.withDescription("Rows per second to control the emit rate.");

	public static final String FIELDS = "fields";
	public static final String KIND = "kind";
	public static final String START = "start";
	public static final String END = "end";
	public static final String MIN = "min";
	public static final String MAX = "max";
	public static final String LENGTH = "length";

	public static final String SEQUENCE = "sequence";
	public static final String RANDOM = "random";

	@Override
	public String factoryIdentifier() {
		return IDENTIFIER;
	}

	@Override
	public Set<ConfigOption<?>> requiredOptions() {
		return new HashSet<>();
	}

	@Override
	public Set<ConfigOption<?>> optionalOptions() {
		Set<ConfigOption<?>> options = new HashSet<>();
		options.add(ROWS_PER_SECOND);
		return options;
	}

	@Override
	public DynamicTableSource createDynamicTableSource(Context context) {
		Configuration options = new Configuration();
		context.getCatalogTable().getOptions().forEach(options::setString);

		TableSchema tableSchema = TableSchemaUtils.getPhysicalSchema(context.getCatalogTable().getSchema());

		DataGenerator[] fieldGenerators = new DataGenerator[tableSchema.getFieldCount()];
		for (int i = 0; i < fieldGenerators.length; i++) {
			fieldGenerators[i] = createDataGenerator(
					tableSchema.getFieldName(i).get(),
					tableSchema.getFieldDataType(i).get(),
					options);
		}

		return new DataGenTableSource(fieldGenerators, tableSchema, options.get(ROWS_PER_SECOND));
	}

	private DataGenerator createDataGenerator(String name, DataType type, ReadableConfig options) {
		String genType = options.get(
				key(FIELDS + "." + name + "." + KIND).stringType().defaultValue(RANDOM));
		switch (genType) {
			case RANDOM:
				return createRandomGenerator(name, type, options);
			case SEQUENCE:
				return createSequenceGenerator(name, type, options);
			default:
				throw new ValidationException("Unsupported generator type: " + genType);
		}
	}

	private DataGenerator createRandomGenerator(String name, DataType type, ReadableConfig options) {
		ConfigOption<Integer> lenKey = key(FIELDS + "." + name + "." + LENGTH)
				.intType().defaultValue(100);
		OptionBuilder minKey = key(FIELDS + "." + name + "." + MIN);
		OptionBuilder maxKey = key(FIELDS + "." + name + "." + MAX);
		switch (type.getLogicalType().getTypeRoot()) {
			case BOOLEAN:
				return RandomGenerator.booleanGenerator();
			case CHAR:
			case VARCHAR:
				int length = options.get(lenKey);
				return new RandomGenerator<StringData>() {
					@Override
					public StringData next() {
						return StringData.fromString(random.nextHexString(length));
					}
				};
			case TINYINT:
				return RandomGenerator.byteGenerator(
						options.get(minKey.intType().defaultValue((int) Byte.MIN_VALUE)).byteValue(),
						options.get(maxKey.intType().defaultValue((int) Byte.MAX_VALUE)).byteValue());
			case SMALLINT:
				return RandomGenerator.shortGenerator(
						options.get(minKey.intType().defaultValue((int) Short.MIN_VALUE)).shortValue(),
						options.get(maxKey.intType().defaultValue((int) Short.MAX_VALUE)).shortValue());
			case INTEGER:
				return RandomGenerator.intGenerator(
						options.get(minKey.intType().defaultValue(Integer.MIN_VALUE)),
						options.get(maxKey.intType().defaultValue(Integer.MAX_VALUE)));
			case BIGINT:
				return RandomGenerator.longGenerator(
						options.get(minKey.longType().defaultValue(Long.MIN_VALUE)),
						options.get(maxKey.longType().defaultValue(Long.MAX_VALUE)));
			case FLOAT:
				return RandomGenerator.floatGenerator(
						options.get(minKey.floatType().defaultValue(Float.MIN_VALUE)),
						options.get(maxKey.floatType().defaultValue(Float.MAX_VALUE)));
			case DOUBLE:
				return RandomGenerator.doubleGenerator(
						options.get(minKey.doubleType().defaultValue(Double.MIN_VALUE)),
						options.get(maxKey.doubleType().defaultValue(Double.MAX_VALUE)));
			default:
				throw new ValidationException("Unsupported type: " + type);
		}
	}

	private DataGenerator createSequenceGenerator(String name, DataType type, ReadableConfig options) {
		OptionBuilder startKey = key(FIELDS + "." + name + "." + START);
		OptionBuilder endKey = key(FIELDS + "." + name + "." + END);

		options.getOptional(startKey.stringType().noDefaultValue()).orElseThrow(
				() -> new ValidationException("Could not find required property '" + startKey + "'."));
		options.getOptional(endKey.stringType().noDefaultValue()).orElseThrow(
				() -> new ValidationException("Could not find required property '" + endKey + "'."));

		switch (type.getLogicalType().getTypeRoot()) {
			case CHAR:
			case VARCHAR:
			return new SequenceGenerator<StringData>(
					options.get(startKey.longType().noDefaultValue()),
					options.get(endKey.longType().noDefaultValue())) {
				@Override
				public StringData next() {
					return StringData.fromString(valuesToEmit.poll().toString());
				}
			};
			case TINYINT:
				return SequenceGenerator.byteGenerator(
						options.get(startKey.intType().noDefaultValue()).byteValue(),
						options.get(endKey.intType().noDefaultValue()).byteValue());
			case SMALLINT:
				return SequenceGenerator.shortGenerator(
						options.get(startKey.intType().noDefaultValue()).shortValue(),
						options.get(endKey.intType().noDefaultValue()).shortValue());
			case INTEGER:
				return SequenceGenerator.intGenerator(
						options.get(startKey.intType().noDefaultValue()),
						options.get(endKey.intType().noDefaultValue()));
			case BIGINT:
				return SequenceGenerator.longGenerator(
						options.get(startKey.longType().noDefaultValue()),
						options.get(endKey.longType().noDefaultValue()));
			case FLOAT:
				return SequenceGenerator.floatGenerator(
						options.get(startKey.intType().noDefaultValue()).shortValue(),
						options.get(endKey.intType().noDefaultValue()).shortValue());
			case DOUBLE:
				return SequenceGenerator.doubleGenerator(
						options.get(startKey.intType().noDefaultValue()),
						options.get(endKey.intType().noDefaultValue()));
			default:
				throw new ValidationException("Unsupported type: " + type);
		}
	}
}