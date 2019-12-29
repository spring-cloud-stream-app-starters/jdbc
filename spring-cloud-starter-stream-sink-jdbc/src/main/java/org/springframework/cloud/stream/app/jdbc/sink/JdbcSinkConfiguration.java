/*
 * Copyright 2015-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.app.jdbc.sink;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.app.jdbc.DefaultInitializationScriptResource;
import org.springframework.cloud.stream.app.jdbc.ShorthandMapConverter;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.aggregator.DefaultAggregatingMessageGroupProcessor;
import org.springframework.integration.aggregator.ExpressionEvaluatingCorrelationStrategy;
import org.springframework.integration.aggregator.MessageCountReleaseStrategy;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.config.AggregatorFactoryBean;
import org.springframework.integration.expression.ExpressionUtils;
import org.springframework.integration.expression.ValueExpression;
import org.springframework.integration.jdbc.JdbcMessageHandler;
import org.springframework.integration.jdbc.SqlParameterSourceFactory;
import org.springframework.integration.json.JsonPropertyAccessor;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.integration.support.MutableMessage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A module that writes its incoming payload to an RDBMS using JDBC.
 *
 * @author Eric Bottard
 * @author Thomas Risberg
 * @author Robert St. John
 * @author Oliver Flasch
 * @author Artem Bilan
 * @author Soby Chacko
 * @author Szabolcs Stremler
 */
@EnableBinding(Sink.class)
@EnableConfigurationProperties(JdbcSinkProperties.class)
public class JdbcSinkConfiguration {

	private static final Log logger = LogFactory.getLog(JdbcSinkConfiguration.class);

	private static final Object NOT_SET = new Object();

	private SpelExpressionParser spelExpressionParser = new SpelExpressionParser();

	@Autowired
	private BeanFactory beanFactory;

	@Autowired
	private JdbcSinkProperties properties;

	private EvaluationContext evaluationContext;

	@Bean
	@Primary
	@ServiceActivator(inputChannel = Sink.INPUT)
	FactoryBean<MessageHandler> aggregatorFactoryBean(MessageGroupStore messageGroupStore) {
		AggregatorFactoryBean aggregatorFactoryBean = new AggregatorFactoryBean();
		aggregatorFactoryBean
				.setCorrelationStrategy(new ExpressionEvaluatingCorrelationStrategy("payload.getClass().name"));
		aggregatorFactoryBean.setReleaseStrategy(new MessageCountReleaseStrategy(this.properties.getBatchSize()));
		if (this.properties.getIdleTimeout() >= 0) {
			aggregatorFactoryBean.setGroupTimeoutExpression(new ValueExpression<>(this.properties.getIdleTimeout()));
		}
		aggregatorFactoryBean.setMessageStore(messageGroupStore);
		aggregatorFactoryBean.setProcessorBean(new DefaultAggregatingMessageGroupProcessor());
		aggregatorFactoryBean.setExpireGroupsUponCompletion(true);
		aggregatorFactoryBean.setSendPartialResultOnExpiry(true);
		aggregatorFactoryBean.setOutputChannelName("toSink");
		return aggregatorFactoryBean;
	}

	@Bean
	@ServiceActivator(inputChannel = "toSink")
	public JdbcMessageHandler jdbcMessageHandler(DataSource dataSource) {
		final MultiValueMap<String, Expression> columnExpressionVariations = new LinkedMultiValueMap<>();
		for (Map.Entry<String, String> entry : this.properties.getColumnsMap().entrySet()) {
			String value = entry.getValue();
			columnExpressionVariations.add(entry.getKey(), this.spelExpressionParser.parseExpression(value));
			if (!value.startsWith("payload")) {
				String qualified = "payload." + value;
				try {
					columnExpressionVariations.add(entry.getKey(),
							this.spelExpressionParser.parseExpression(qualified));
				}
				catch (SpelParseException e) {
					logger.info("failed to parse qualified fallback expression " + qualified +
							"; be sure your expression uses the 'payload.' prefix where necessary");
				}
			}
		}
		JdbcMessageHandler jdbcMessageHandler = new JdbcMessageHandler(dataSource,
				generateSql(this.properties.getTableName(), columnExpressionVariations.keySet())) {

			@Override
			protected void handleMessageInternal(final Message<?> message) {
				Message<?> convertedMessage = message;
				if (message.getPayload() instanceof byte[] || message.getPayload() instanceof Iterable){

					final String contentType = message.getHeaders().containsKey(MessageHeaders.CONTENT_TYPE)
							? message.getHeaders().get(MessageHeaders.CONTENT_TYPE).toString()
							: BindingProperties.DEFAULT_CONTENT_TYPE.toString();
					if (message.getPayload() instanceof Iterable) {
						Stream<Object> messageStream =
								StreamSupport.stream(((Iterable<?>) message.getPayload()).spliterator(),false)
										.map(payload -> {
											if (payload instanceof byte[]) {
												return convertibleContentType(contentType) ?
														new String(((byte[]) payload)) : payload;
											}
											else {
												return payload;
											}
										});
						convertedMessage = new MutableMessage<>(messageStream.collect(Collectors.toList()), message.getHeaders());
					}
					else {
						if (convertibleContentType(contentType)) {
							convertedMessage = new MutableMessage<>(new String(((byte[]) message.getPayload())), message.getHeaders());
						}
					}
				}
				super.handleMessageInternal(convertedMessage);
			}
		};
		SqlParameterSourceFactory parameterSourceFactory =
				new ParameterFactory(columnExpressionVariations, this.evaluationContext);
		jdbcMessageHandler.setSqlParameterSourceFactory(parameterSourceFactory);
		return jdbcMessageHandler;
	}

	private boolean convertibleContentType(String contentType) {
		return contentType.contains("text") || contentType.contains("json") || contentType.contains("x-spring-tuple");
	}

	@ConditionalOnProperty("jdbc.initialize")
	@Bean
	public DataSourceInitializer nonBootDataSourceInitializer(DataSource dataSource, ResourceLoader resourceLoader) {
		DataSourceInitializer dataSourceInitializer = new DataSourceInitializer();
		dataSourceInitializer.setDataSource(dataSource);
		ResourceDatabasePopulator databasePopulator = new ResourceDatabasePopulator();
		databasePopulator.setIgnoreFailedDrops(true);
		dataSourceInitializer.setDatabasePopulator(databasePopulator);
		if ("true".equals(properties.getInitialize())) {
			databasePopulator.addScript(
					new DefaultInitializationScriptResource(this.properties.getTableName(),
							this.properties.getColumnsMap().keySet()));
		}
		else {
			databasePopulator.addScript(resourceLoader.getResource(this.properties.getInitialize()));
		}
		return dataSourceInitializer;
	}

	@Bean
	MessageGroupStore messageGroupStore() {
		SimpleMessageStore messageGroupStore = new SimpleMessageStore();
		messageGroupStore.setTimeoutOnIdle(true);
		messageGroupStore.setCopyOnGet(false);
		return messageGroupStore;
	}

	@Bean
	public static ShorthandMapConverter shorthandMapConverter() {
		return new ShorthandMapConverter();
	}

	@PostConstruct
	public void afterPropertiesSet() {
		this.evaluationContext = ExpressionUtils.createStandardEvaluationContext(this.beanFactory);
	}

	private String generateSql(String tableName, Set<String> columns) {
		StringBuilder builder = new StringBuilder("INSERT INTO ");
		StringBuilder questionMarks = new StringBuilder(") VALUES (");
		builder.append(tableName).append("(");
		int i = 0;

		for (String column : columns) {
			if (i++ > 0) {
				builder.append(", ");
				questionMarks.append(", ");
			}
			builder.append(column);
			questionMarks.append(':').append(column);
		}
		builder.append(questionMarks).append(")");
		return builder.toString();
	}

	private static final class ParameterFactory implements SqlParameterSourceFactory {

		private final MultiValueMap<String, Expression> columnExpressions;

		private final EvaluationContext context;

		ParameterFactory(MultiValueMap<String, Expression> columnExpressions, EvaluationContext context) {
			this.columnExpressions = columnExpressions;
			this.context = context;
		}

		@Override
		public SqlParameterSource createParameterSource(Object o) {
			if (!(o instanceof Message)) {
				throw new IllegalArgumentException("Unable to handle type " + o.getClass().getName());
			}
			Message<?> message = (Message<?>) o;
			MapSqlParameterSource parameterSource = new MapSqlParameterSource();
			for (Map.Entry<String, List<Expression>> entry : this.columnExpressions.entrySet()) {
				String key = entry.getKey();
				List<Expression> spels = entry.getValue();
				Object value = NOT_SET;
				EvaluationException lastException = null;
				for (Expression spel : spels) {
					try {
						value = spel.getValue(context, message);
						break;
					}
					catch (EvaluationException e) {
						lastException = e;
					}
				}
				if (value == NOT_SET) {
					if (lastException != null) {
						logger.info("Could not find value for column '" + key + "': " + lastException.getMessage());
					}
					parameterSource.addValue(key, null);
				}
				else {
					if (value instanceof JsonPropertyAccessor.ToStringFriendlyJsonNode) {
						// Need to do some reflection until we have a getter for the Node
						DirectFieldAccessor dfa = new DirectFieldAccessor(value);
						JsonNode node = (JsonNode) dfa.getPropertyValue("node");
						Object valueToUse;
						if (node == null || node.isNull()) {
							valueToUse = null;
						}
						else if (node.isNumber()) {
							valueToUse = node.numberValue();
						}
						else if (node.isBoolean()) {
							valueToUse = node.booleanValue();
						}
						else {
							valueToUse = node.textValue();
						}
						parameterSource.addValue(key, valueToUse);
					}
					else {
						parameterSource.addValue(key, value);
					}
				}
			}
			return parameterSource;
		}

	}

}
